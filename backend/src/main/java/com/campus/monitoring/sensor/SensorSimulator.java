package com.campus.monitoring.sensor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.campus.monitoring.config.MqttBus;
import com.campus.monitoring.persistencia.Sensor;
import com.campus.monitoring.persistencia.SensorRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Simulador de los 12 sensores de la Tabla 1 del documento.
 *
 * - Publica en campus/{sede}/{variable}/{sensorId} con QoS 1 (Pub/Sus).
 * - Se registra en la BD al arrancar (HDU-05, los crea si no existen).
 * - Escucha control/simulador/# para las ordenes de falla del panel
 *   (estructura lista para la Ronda 2: apagar / restaurar sensor).
 */
@Component
public class SensorSimulator {

    private static final Logger log = LoggerFactory.getLogger(SensorSimulator.class);

    private final MqttBus mqtt;
    private final SensorRepository sensores;
    private final ObjectMapper json = new ObjectMapper();

    /** Sensores apagados por orden del panel (Ronda 2) */
    private final Map<String, Boolean> apagados = new ConcurrentHashMap<>();

    @Value("${sensor.frecuencia-ms:5000}")
    private long frecuenciaMs;

    public SensorSimulator(MqttBus mqtt, SensorRepository sensores) {
        this.mqtt = mqtt;
        this.sensores = sensores;
    }

    /** Catalogo de la Tabla 1: id, variable, sede, salon, valor base */
    private List<SensorDef> catalogo() {
        List<SensorDef> defs = new ArrayList<>();
        // Temperatura Sur — 5 sensores, precision +/- 2 °C
        defs.add(new SensorDef("TMP-SUR-01", "temperatura", "Sur", "Salon 101", 23.0));
        defs.add(new SensorDef("TMP-SUR-02", "temperatura", "Sur", "Salon 102", 24.0));
        defs.add(new SensorDef("TMP-SUR-03", "temperatura", "Sur", "Salon 103", 22.5));
        defs.add(new SensorDef("TMP-SUR-04", "temperatura", "Sur", "Auditorio", 25.0));
        defs.add(new SensorDef("TMP-SUR-05", "temperatura", "Sur", "Biblioteca", 23.5));
        // Temperatura Federman — 3 sensores
        defs.add(new SensorDef("TMP-FED-01", "temperatura", "Federman", "Salon 201", 23.0));
        defs.add(new SensorDef("TMP-FED-02", "temperatura", "Federman", "Salon 202", 24.5));
        defs.add(new SensorDef("TMP-FED-03", "temperatura", "Federman", "Laboratorio", 22.0));
        // Calidad del aire — 1 por sede, alerta al alcanzar 900 ppm
        defs.add(new SensorDef("AQI-SUR-01", "calidad-aire", "Sur", "Pasillo principal", 520.0));
        defs.add(new SensorDef("AQI-FED-01", "calidad-aire", "Federman", "Pasillo principal", 480.0));
        // Consumo — Sur, sin notificacion
        defs.add(new SensorDef("ELEC-SUR-01", "consumo-electrico", "Sur", "Aduanas electricas", 145.0));
        defs.add(new SensorDef("AGUA-SUR-01", "consumo-agua", "Sur", "Cisterna", 1.8));
        return defs;
    }

    /** Registra los sensores en la BD si aun no existen (HDU-05) */
    @EventListener(ApplicationReadyEvent.class)
    public void registrarSensores() {
        int creados = 0;
        for (SensorDef def : catalogo()) {
            if (!sensores.existsById(def.id())) {
                sensores.save(new Sensor(def.id(), def.variable(), def.sede(), def.salon(), 5));
                creados++;
            }
        }
        // Orden de falla del panel: la escucha siempre, aunque este MVP solo la registre
        mqtt.suscribir("campus-simulador", "control/simulador/#", (topic, mensaje) -> {
            // TODO (Ronda 2): interpretar apagar/restaurar sobre el sensor indicado
            log.warn("[CONTROL] Orden recibida (pendiente Ronda 2): {} -> {}", topic, mensaje);
        });
        log.info("[SIMULADOR] Catalogo listo: {} sensores nuevos, {} en total (Tabla 1)",
                creados, sensores.count());
    }

    /** Publica todas las mediciones en cada ciclo (Tabla 1: frecuencia configurable) */
    @Scheduled(fixedDelayString = "${sensor.frecuencia-ms:5000}")
    public void publicarMediciones() {
        Instant ahora = Instant.now();
        for (SensorDef def : catalogo()) {
            if (Boolean.TRUE.equals(apagados.get(def.id()))) {
                continue; // apagado por orden del panel (Ronda 2)
            }
            double valor = medir(def);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("sensorId", def.id());
            payload.put("variable", def.variable());
            payload.put("sede", def.sede());
            payload.put("salon", def.salon());
            payload.put("valor", valor);
            payload.put("fecha", ahora.toString());
            try {
                String topic = "campus/%s/%s/%s".formatted(
                        def.sede().toLowerCase(), def.variable(), def.id());
                mqtt.publicar(topic, json.writeValueAsString(payload));
            } catch (Exception e) {
                log.error("[SIMULADOR] No se pudo publicar {}: {}", def.id(), e.getMessage());
            }
        }
    }

    /**
     * Genera un valor creíble por variable:
     * - temperatura: caminata aleatoria alrededor de la base, +/- 2 °C (precision Tabla 1)
     * - calidad-aire: base 400-650 ppm con picos ocasionales que cruzan 900
     * - consumo electrico/agua: valores flotantes estables
     */
    private double medir(SensorDef def) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        return switch (def.variable()) {
            case "temperatura" -> redondear(def.base() + rnd.nextDouble(-2, 2), 1);
            case "calidad-aire" -> {
                double pico = rnd.nextDouble() < 0.08 ? rnd.nextDouble(400, 550) : 0;
                yield redondear(Math.max(380, Math.min(1200, def.base() + pico + rnd.nextDouble(-60, 60))), 0);
            }
            case "consumo-electrico" -> redondear(def.base() + rnd.nextDouble(-8, 8), 1);
            case "consumo-agua" -> redondear(Math.max(0, def.base() + rnd.nextDouble(-0.4, 0.4)), 2);
            default -> def.base();
        };
    }

    private double redondear(double v, int decimales) {
        double p = Math.pow(10, decimales);
        return Math.round(v * p) / p;
    }

    /** Apaga un sensor (usado por el panel de control) */
    public void apagar(String sensorId) {
        apagados.put(sensorId, true);
        log.warn("[SIMULADOR] Sensor {} APAGADO por orden del panel", sensorId);
    }

    /** Reactiva un sensor apagado */
    public void restaurar(String sensorId) {
        apagados.remove(sensorId);
        log.info("[SIMULADOR] Sensor {} restaurado", sensorId);
    }

    public Map<String, Boolean> estadoApagados() {
        return new LinkedHashMap<>(apagados);
    }

    private record SensorDef(String id, String variable, String sede, String salon, double base) {
    }
}
