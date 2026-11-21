package com.campus.monitoring.persistencia;

import java.time.Instant;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import com.campus.monitoring.config.ComponenteCaidoException;
import com.campus.monitoring.config.MqttBus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Servicio de persistencia — unico acceso a la base de datos (Tabla 14).
 *
 * - interno/procesadas -> guarda la medicion y actualiza el estado del sensor.
 * - interno/logs       -> guarda el registro de errores (HDU-07).
 *
 * El duplicado (sensor + fecha) lo rechaza la restriccion unica de la BD;
 * aqui se captura para que no rompa el flujo (HDU-03).
 *
 * DEMO DE FALLAS: el flag "bdCaida" simula la caida de la base de datos.
 * Mientras este activo, guardarMedicion lanza ComponenteCaidoException antes
 * de tocar JPA: los mensajes quedan PENDIENTES en la cola de entrada (sin
 * gastar reintentos) hasta que el panel restaure; ningun dato se pierde.
 */
@Component
public class PersistenciaService {

    private static final Logger log = LoggerFactory.getLogger(PersistenciaService.class);

    private final MqttBus mqtt;
    private final MedicionRepository mediciones;
    private final ErrorRegistroRepository errores;
    private final SensorRepository sensores;
    private final ObjectMapper json = new ObjectMapper();

    /** Caida simulada de la base de datos (panel de control de fallas) */
    private volatile boolean bdCaida = false;

    public PersistenciaService(MqttBus mqtt, MedicionRepository mediciones,
                               ErrorRegistroRepository errores, SensorRepository sensores) {
        this.mqtt = mqtt;
        this.mediciones = mediciones;
        this.errores = errores;
        this.sensores = sensores;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void suscribir() {
        mqtt.suscribir("campus-persistencia", "interno/procesadas", this::guardarMedicion);
        // Los errores de recepcion/procesamiento se archivan (HDU-07)
        mqtt.suscribir("campus-persistencia-log", "interno/logs", this::guardarEvento);
        // Las alertas del heartbeat (T3/HDU-04) tambien quedan en el registro
        mqtt.suscribir("campus-persistencia-alertas", "interno/alertas", this::guardarEvento);
    }

    private void guardarMedicion(String topic, String mensaje) {
        if (bdCaida) {
            // Base de datos fuera de servicio: el bus deja el mensaje
            // PENDIENTE y reintenta en el proximo tick (ver MqttBus).
            throw new ComponenteCaidoException("base-de-datos");
        }
        try {
            JsonNode n = json.readTree(mensaje);
            Instant recibida = Instant.now();
            Medicion m = new Medicion(
                    n.get("sensorId").asText(),
                    n.get("variable").asText(),
                    n.get("sede").asText(),
                    n.path("salon").asText(null),
                    n.get("valor").asDouble(),
                    Instant.parse(n.get("fecha").asText()),
                    recibida);
            try {
                mediciones.save(m);
            } catch (DataIntegrityViolationException dup) {
                // HDU-03: duplicado (sensor + fecha) -> se ignora sin frenar el flujo.
                // Aun asi se refresca el heartbeat: con QoS 1 las reentregas son
                // normales y no deben dejar el sensor "apagado" en el tablero.
                log.debug("[PERSISTENCIA] Medicion duplicada ignorada: {} @ {}",
                        m.getSensorId(), m.getFecha());
                refrescarSensor(m);
                return;
            }
            // Actualiza la ultima medicion del sensor (base del heartbeat HDU-04)
            refrescarSensor(m);
            long latenciaMs = java.time.Duration.between(m.getFecha(), recibida).toMillis();
            log.debug("[PERSISTENCIA] Guardada {} = {} (latencia {} ms)",
                    m.getSensorId(), m.getValor(), latenciaMs);
        } catch (Exception e) {
            log.error("[PERSISTENCIA] Error guardando medicion: {}", e.getMessage());
            publicarError("ERROR_COMUNICACION", null, "Fallo al persistir: " + e.getMessage());
        }
    }

    /**
     * Actualiza el estado del sensor con la medicion recibida (heartbeat HDU-04).
     * Solo avanza hacia adelante: una reentrega antigua no debe atrasar la
     * ultima fecha conocida ni pisar un estado ya actualizado.
     */
    private void refrescarSensor(Medicion m) {
        sensores.findById(m.getSensorId()).ifPresent(s -> {
            Instant ultima = s.getUltimaMedicionEn();
            if (ultima == null || m.getFecha().isAfter(ultima)) {
                s.setUltimaMedicionEn(m.getFecha());
                s.setEstado("ACTIVO");
                sensores.save(s);
            }
        });
    }

    /**
     * Guarda un evento de interno/logs o interno/alertas en la tabla de
     * errores (HDU-07). El heartbeat publica SENSOR_INACTIVO /
     * SENSOR_RECUPERADO por interno/alertas y quedan auditables aqui.
     */
    private void guardarEvento(String topic, String mensaje) {
        try {
            JsonNode n = json.readTree(mensaje);
            ErrorRegistro e = new ErrorRegistro(
                    n.path("tipo").asText("DESCONOCIDO"),
                    n.path("sensorId").isMissingNode() || n.path("sensorId").isNull()
                            ? null
                            : n.path("sensorId").asText(null),
                    n.path("fecha").isMissingNode()
                            ? Instant.now()
                            : Instant.parse(n.get("fecha").asText()),
                    n.path("descripcion").asText(""));
            errores.save(e);
            log.warn("[PERSISTENCIA] Evento registrado ({}): {} - {}",
                    topic, e.getTipo(), e.getDescripcion());
        } catch (Exception ex) {
            log.error("[PERSISTENCIA] Error guardando evento de {}: {}", topic, ex.getMessage());
        }
    }

    private void publicarError(String tipo, String sensorId, String descripcion) {
        try {
            var nodo = json.createObjectNode();
            nodo.put("tipo", tipo);
            nodo.put("sensorId", sensorId);
            nodo.put("fecha", Instant.now().toString());
            nodo.put("descripcion", descripcion);
            mqtt.publicar("interno/logs", json.writeValueAsString(nodo));
        } catch (Exception ignored) {
            // no publicar errores de errores
        }
    }

    /** Simula la caida de la base de datos: nada se guarda, la cola crece */
    public void caer() {
        bdCaida = true;
        log.warn("[PERSISTENCIA] Base de datos CAIDA por orden del panel: "
                + "las mediciones se acumulan en cola hasta restaurar");
    }

    /** Restaura la base de datos: el proximo tick vacia la cola pendiente */
    public void levantar() {
        bdCaida = false;
        log.info("[PERSISTENCIA] Base de datos restaurada: reanudando el guardado");
    }

    public boolean estaCaida() {
        return bdCaida;
    }

    /** Diagnostico rapido para el panel */
    public Map<String, Object> resumen() {
        return Map.of(
                "mediciones", mediciones.count(),
                "errores", errores.count(),
                "sensores", sensores.count());
    }
}
