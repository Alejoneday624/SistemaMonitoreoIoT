package com.campus.monitoring.persistencia;

import java.time.Instant;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

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
 */
@Component
public class PersistenciaService {

    private static final Logger log = LoggerFactory.getLogger(PersistenciaService.class);

    private final MqttBus mqtt;
    private final MedicionRepository mediciones;
    private final ErrorRegistroRepository errores;
    private final SensorRepository sensores;
    private final ObjectMapper json = new ObjectMapper();

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
        // Los errores de recepcion/procesamiento tambien se archivan (HDU-07)
        mqtt.suscribir("campus-persistencia-log", "interno/logs", this::guardarError);
    }

    private void guardarMedicion(String topic, String mensaje) {
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

    private void guardarError(String topic, String mensaje) {
        try {
            JsonNode n = json.readTree(mensaje);
            ErrorRegistro e = new ErrorRegistro(
                    n.path("tipo").asText("DESCONOCIDO"),
                    n.path("sensorId").asText(null),
                    n.path("fecha").isMissingNode()
                            ? Instant.now()
                            : Instant.parse(n.get("fecha").asText()),
                    n.path("descripcion").asText(""));
            errores.save(e);
            log.warn("[PERSISTENCIA] Error registrado: {} - {}", e.getTipo(), e.getDescripcion());
        } catch (Exception ex) {
            log.error("[PERSISTENCIA] Error guardando registro de errores: {}", ex.getMessage());
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

    /** Diagnostico rapido para el panel */
    public Map<String, Object> resumen() {
        return Map.of(
                "mediciones", mediciones.count(),
                "errores", errores.count(),
                "sensores", sensores.count());
    }
}
