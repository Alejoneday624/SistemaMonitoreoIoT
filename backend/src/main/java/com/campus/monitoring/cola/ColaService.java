package com.campus.monitoring.cola;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Colas duraderas de cada servicio, persistidas en PostgreSQL.
 *
 * Por que archivos y no el disco local: en Render (y en cualquier PaaS) el
 * disco es efimero y se borra en cada redeploy; la base de datos sobrevive.
 * Asi la cola sigue existiendo aunque el proceso desaparezca por completo.
 *
 * Uso (lo hace MqttBus):
 * - encolar(cola, topic, payload): guardar antes de confiar en la red.
 * - siguiente(cola) -> quitar(id): tomar el mas viejo (FIFO) y retirarlo
 *   solo cuando el broker confirmo (salida) o el manejador termino (entrada).
 * - registrarFallo: reintentos acotados -> FALLIDO (no bloquea la cola).
 */
@Service
public class ColaService {

    private static final Logger log = LoggerFactory.getLogger(ColaService.class);

    /** Cola unica de publicaciones (outbox) */
    public static final String SALIDA = "salida";

    private final ColaMensajesRepository repo;
    private final int maxPendientes;
    private final int reintentosMax;
    private final int fallidosDias;

    public ColaService(ColaMensajesRepository repo,
                       @Value("${cola.max-pendientes:5000}") int maxPendientes,
                       @Value("${cola.reintentos-max:5}") int reintentosMax,
                       @Value("${cola.fallidos-dias:7}") int fallidosDias) {
        this.repo = repo;
        this.maxPendientes = maxPendientes;
        this.reintentosMax = reintentosMax;
        this.fallidosDias = fallidosDias;
    }

    /**
     * Agrega un mensaje a una cola.
     *
     * Si la cola llega al tope (broker caido mucho tiempo) se descartan los
     * mensajes MAS VIEJOS: para telemetria lo valioso es lo reciente y asi
     * nunca se llena el disco de la base. Queda trazado en el log.
     */
    @Transactional
    public long encolar(String cola, String topic, String payload) {
        while (repo.countByColaAndEstado(cola, ColaMensajes.PENDIENTE) >= maxPendientes) {
            Optional<ColaMensajes> viejo = repo.findFirstByColaAndEstadoOrderByIdAsc(cola, ColaMensajes.PENDIENTE);
            if (viejo.isEmpty()) {
                break;
            }
            log.warn("[COLA] '{}' al tope ({}): se descarta el mensaje mas antiguo #{} de {}",
                    cola, maxPendientes, viejo.get().getId(), viejo.get().getTopic());
            repo.delete(viejo.get());
        }
        return repo.save(new ColaMensajes(cola, topic, payload, Instant.now())).getId();
    }

    /** Siguiente mensaje pendiente de la cola (mas viejo primero) */
    public Optional<ColaMensajes> siguiente(String cola) {
        return repo.findFirstByColaAndEstadoOrderByIdAsc(cola, ColaMensajes.PENDIENTE);
    }

    /** Retira un mensaje ya procesado/confirmado */
    @Transactional
    public void quitar(Long id) {
        repo.deleteById(id);
    }

    /** Cuantos pendientes quedan (diagnosticos y tests) */
    public long pendientes(String cola) {
        return repo.countByColaAndEstado(cola, ColaMensajes.PENDIENTE);
    }

    /**
     * Registra un fallo de procesamiento.
     *
     * @return true si el mensaje agoto los reintentos y quedo FALLIDO: en ese
     *         caso la cola lo salta para que un mensaje toxico no bloquee a
     *         los que vienen detras (y queda en la tabla para inspeccion).
     */
    @Transactional
    public boolean registrarFallo(String cola, Long id, int reintentos) {
        return repo.findById(id).map(m -> {
            m.setIntentos(m.getIntentos() + 1);
            boolean agotado = m.getIntentos() >= reintentos;
            if (agotado) {
                m.setEstado(ColaMensajes.FALLIDO);
            }
            repo.save(m);
            log.warn("[COLA] '{}' fallo #{}/{} del mensaje #{}{}",
                    cola, m.getIntentos(), reintentos, id, agotado ? " -> FALLIDO" : "");
            return agotado;
        }).orElse(true); // ya no existe: nada que reintentar
    }

    public int reintentosMax() {
        return reintentosMax;
    }

    /**
     * Housekeeping: los FALLIDO se conservan un tiempo y luego se purgan
     * (tambien evita que crezcan si un sensor publica siempre mal).
     */
    @Scheduled(fixedDelayString = "${cola.purgar-ms:3600000}")
    @Transactional
    public void purgarFallidos() {
        try {
            repo.deleteByEstadoAndCreadoEnBefore(
                    ColaMensajes.FALLIDO, Instant.now().minus(Duration.ofDays(fallidosDias)));
        } catch (Exception e) {
            log.debug("[COLA] Purga de fallidos no ejecutada: {}", e.getMessage());
        }
    }
}
