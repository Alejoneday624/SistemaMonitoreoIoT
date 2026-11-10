package com.campus.monitoring.recepcion;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.campus.monitoring.config.MqttBus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Servicio de recepcion de datos (HDU-02 / HDU-03).
 *
 * - Se suscribe a campus/# (todas las sedes y variables).
 * - Valida formato y campos obligatorios; lo invalido NO pasa al procesamiento
 *   y se registra en interno/logs.
 * - Lo valido se reenvia a interno/validadas (QoS 1).
 * - Si procesamiento no esta disponible, el broker encola (QoS 1 + sesion
 *   persistente) y al reconectar llegan los pendientes (E9/E11).
 */
@Component
public class RecepcionService {

    private static final Logger log = LoggerFactory.getLogger(RecepcionService.class);

    private final MqttBus mqtt;
    private final ObjectMapper json = new ObjectMapper();

    public RecepcionService(MqttBus mqtt) {
        this.mqtt = mqtt;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void suscribir() {
        mqtt.suscribir("campus-recepcion", "campus/#", this::procesarMensaje);
    }

    void procesarMensaje(String topic, String mensaje) {
        try {
            JsonNode n = json.readTree(mensaje);
            String error = validar(n);
            if (error != null) {
                // HDU-03: medicion invalida -> interno/logs, no afecta al resto
                log.warn("[RECEPCION] Medicion rechazada: {}", error);
                publicarError("MEDICION_INVALIDA", n.path("sensorId").asText(null), error);
                return;
            }
            // Formato correcto: se reenvia al procesamiento
            mqtt.publicar("interno/validadas", mensaje);
        } catch (Exception e) {
            log.error("[RECEPCION] Mensaje ilegible en {}: {}", topic, e.getMessage());
            publicarError("MEDICION_INVALIDA", null, "JSON ilegible: " + e.getMessage());
        }
    }

    /** Campos obligatorios: sensorId, variable, sede, valor numerico, fecha */
    private String validar(JsonNode n) {
        if (!n.hasNonNull("sensorId")) return "falta sensorId";
        if (!n.hasNonNull("variable")) return "falta variable";
        if (!n.hasNonNull("sede")) return "falta sede";
        if (!n.hasNonNull("valor") || !n.get("valor").isNumber()) return "valor no numerico";
        if (!n.hasNonNull("fecha")) return "falta fecha";
        try {
            Instant.parse(n.get("fecha").asText());
        } catch (Exception e) {
            return "fecha invalida (se espera ISO-8601)";
        }
        return null;
    }

    private void publicarError(String tipo, String sensorId, String descripcion) {
        try {
            var nodo = json.createObjectNode();
            nodo.put("tipo", tipo);
            nodo.put("sensorId", sensorId);
            nodo.put("fecha", Instant.now().toString());
            nodo.put("descripcion", descripcion);
            mqtt.publicar("interno/logs", json.writeValueAsString(nodo));
        } catch (Exception e) {
            log.error("[RECEPCION] No se pudo publicar el error: {}", e.getMessage());
        }
    }
}
