package com.campus.monitoring.procesamiento;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.campus.monitoring.config.MqttBus;

/**
 * Servicio de procesamiento — STUB de la Ronda 1.
 *
 * Recibe de interno/validadas y reenvia a interno/procesadas. Deja el flujo
 * completo montado para que el tablero muestre datos reales end-to-end.
 *
 * TODO (Ronda 2 · HDU-03/HDU-06) — implementar aqui:
 *  1. Validar rangos por variable (temperatura 0-60 °C, calidad-aire 0-5000 ppm...)
 *     -> fuera de rango: publicar en interno/logs y NO reenviar a procesadas.
 *  2. Detectar duplicados (sensorId + fecha) antes de reenviar.
 *  3. Evaluar umbrales configurables (HDU-12) y publicar eventos de alerta
 *     en interno/alertas cuando se supere el limite (ej. 900 ppm).
 */
@Component
public class ProcesamientoService {

    private static final Logger log = LoggerFactory.getLogger(ProcesamientoService.class);

    private final MqttBus mqtt;

    public ProcesamientoService(MqttBus mqtt) {
        this.mqtt = mqtt;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void suscribir() {
        mqtt.suscribir("campus-procesamiento", "interno/validadas", (topic, mensaje) -> {
            // STUB: passthrough hacia persistencia / monitoreo / alertas
            mqtt.publicar("interno/procesadas", mensaje);
            log.debug("[PROCESAMIENTO] Medicion derivada a interno/procesadas");
        });
    }
}
