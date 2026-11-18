package com.campus.monitoring.recepcion;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.campus.monitoring.config.ComponenteCaidoException;
import com.campus.monitoring.config.MqttBus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Servicio de recepcion de datos.
 *
 * - Se suscribe a campus/# (todas las sedes y variables).
 * - Valida formato y campos obligatorios; lo invalido NO pasa al procesamiento
 *   y se registra en interno/logs.
 * - Lo valido se reenvia a interno/validadas (QoS 1).
 * - Si procesamiento no esta disponible, el broker encola (QoS 1 + sesion
 *   persistente) y al reconectar llegan los pendientes.
 *
 * DEMO DE FALLAS: el flag "caido" simula la caida de este servicio. Mientras
 * este activo, procesarMensaje lanza ComponenteCaidoException: los mensajes
 * quedan PENDIENTES en la cola de entrada (sin gastar reintentos) hasta que
 * el panel los restaure.
 */
@Component
public class RecepcionService {

    private static final Logger log = LoggerFactory.getLogger(RecepcionService.class);

    private final MqttBus mqtt;
    private final ObjectMapper json = new ObjectMapper();

    /** Caida simulada del servicio (panel de control de fallas) */
    private volatile boolean caido = false;

    public RecepcionService(MqttBus mqtt) {
        this.mqtt = mqtt;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void suscribir() {
        mqtt.suscribir("campus-recepcion", "campus/#", this::procesarMensaje);
    }

    void procesarMensaje(String topic, String mensaje) {
        if (caido) {
            // Servicio fuera de servicio: el bus deja el mensaje PENDIENTE
            // y reintenta en el proximo tick (ver MqttBus).
            throw new ComponenteCaidoException("recepcion");
        }
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

    /** Simula la caida del servicio: los mensajes se acumulan en cola */
    public void caer() {
        caido = true;
        log.warn("[RECEPCION] Servicio CAIDO por orden del panel: "
                + "los mensajes se acumulan en cola hasta restaurar");
    }

    /** Restaura el servicio: el proximo tick vacia la cola pendiente */
    public void levantar() {
        caido = false;
        log.info("[RECEPCION] Servicio restaurado: reanudando el flujo");
    }

    public boolean estaCaido() {
        return caido;
    }
}
