package com.campus.monitoring.sensor;

import java.time.Instant;
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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Simulador de los sensores de la Tabla 1 del documento.
 *
 * - Publica en campus/{sede}/{variable}/{sensorId} con QoS 1 (Pub/Sus).
 * - Se registra en la BD al arrancar (HDU-05): si la tabla esta VACIA siembra
 *   el catalogo por defecto; si no, respeta lo que haya (asi un sensor
 *   eliminado con el CRUD no resucita al reiniciar) y le backfillea valorBase.
 * - El catalogo se lee de la BD en cada ciclo: agregar/modificar/eliminar un
 *   sensor desde la API o la UI se refleja sin tocar codigo ni reiniciar
 *   (modificabilidad: el simulador ES el unico punto que conoce el catalogo).
 * - Escucha control/simulador/# para las ordenes de falla del panel
 *   (apagar / restaurar sensor — la "ruleta" del botón de panico).
 */
@Component
public class SensorSimulator {

    private static final Logger log = LoggerFactory.getLogger(SensorSimulator.class);

    private final MqttBus mqtt;
    private final SensorRepository sensores;
    private final ObjectMapper json = new ObjectMapper();

    /** Sensores apagados por orden del panel (ruleta / toggle individual) */
    private final Map<String, Boolean> apagados = new ConcurrentHashMap<>();

    @Value("${sensor.frecuencia-ms:5000}")
    private long frecuenciaMs;

    public SensorSimulator(MqttBus mqtt, SensorRepository sensores) {
        this.mqtt = mqtt;
        this.sensores = sensores;
    }

    /**
     * Catalogo por defecto de la Tabla 1: id, variable, sede, salon, valor
     * base. Solo se usa para sembrar la BD la PRIMERA vez (tabla vacia) y
     * para backfillear valorBase de filas antiguas.
     */
    private List<SensorDef> catalogoPorDefecto() {
        List<SensorDef> defs = new java.util.ArrayList<>();
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

    /** Valor por defecto por variable (para sensores creados con el CRUD) */
    private double valorBasePorDefecto(String variable) {
        return switch (variable) {
            case "temperatura" -> 23.0;
            case "calidad-aire" -> 500.0;
            case "consumo-electrico" -> 145.0;
            case "consumo-agua" -> 1.8;
            default -> 0.0;
        };
    }

    /**
     * Registra los sensores en la BD si aun no existen (HDU-05) y deja
     * siempre escuchando las ordenes de falla del panel (Pub/Sus).
     */
    @EventListener(ApplicationReadyEvent.class)
    public void registrarSensores() {
        int creados = 0;
        if (sensores.count() == 0) {
            // Primera vez (o BD nueva): siembra el catalogo de la Tabla 1
            for (SensorDef def : catalogoPorDefecto()) {
                sensores.save(new Sensor(def.id(), def.variable(), def.sede(), def.salon(), 5)
                        .conValorBase(def.base()));
                creados++;
            }
        } else {
            // Backfill: filas antiguas (antes del CRUD) sin valorBase
            for (Sensor s : sensores.findAll()) {
                if (s.getValorBase() == null) {
                    s.setValorBase(valorBasePorDefecto(s.getVariable()));
                    sensores.save(s);
                }
            }
        }
        // Orden de falla del panel (Pub/Sus puro: la API publica y aqui se actua)
        mqtt.suscribir("campus-simulador", "control/simulador/#", this::interpretarOrden);
        log.info("[SIMULADOR] Catalogo listo: {} nuevos, {} en total (Tabla 1)",
                creados, sensores.count());
    }

    /**
     * Orden de falla recibida via control/simulador/#:
     *   {"accion":"apagar","sensorId":"TMP-SUR-01"}
     *   {"accion":"restaurar"}                    -> restaura todas
     *   {"accion":"restaurar","sensorId":"..."}   -> restaura una
     */
    void interpretarOrden(String topic, String mensaje) {
        try {
            JsonNode n = json.readTree(mensaje);
            String accion = n.path("accion").asText("");
            String sensorId = n.path("sensorId").asText("");
            switch (accion) {
                case "apagar" -> {
                    if (sensorId.isBlank()) {
                        log.warn("[CONTROL] Orden apagar sin sensorId: {}", mensaje);
                    } else {
                        apagar(sensorId);
                    }
                }
                case "restaurar" -> {
                    if (sensorId.isBlank()) {
                        List<String> todos = List.copyOf(apagados.keySet());
                        todos.forEach(this::restaurar);
                        log.info("[CONTROL] Orden restaurar: {} sensor(es) reactivado(s)", todos.size());
                    } else {
                        restaurar(sensorId);
                    }
                }
                default -> log.warn("[CONTROL] Orden desconocida: {}", mensaje);
            }
        } catch (Exception e) {
            log.error("[CONTROL] Orden ilegible en {}: {}", topic, e.getMessage());
        }
    }

    /** Publica todas las mediciones en cada ciclo (Tabla 1: frecuencia configurable) */
    @Scheduled(fixedDelayString = "${sensor.frecuencia-ms:5000}")
    public void publicarMediciones() {
        Instant ahora = Instant.now();
        for (Sensor s : sensores.findAll()) {
            if (Boolean.TRUE.equals(apagados.get(s.getId()))) {
                // Apagado por orden del panel: no publica, pero el resto del
                // sistema sigue vivo. El panel muestra "reintentando".
                log.info("[SIMULADOR] {} apagado: reintentando publicar (sin datos)", s.getId());
                continue;
            }
            double valor = medir(s);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("sensorId", s.getId());
            payload.put("variable", s.getVariable());
            payload.put("sede", s.getSede());
            payload.put("salon", s.getSalon());
            payload.put("valor", valor);
            payload.put("fecha", ahora.toString());
            try {
                String topic = "campus/%s/%s/%s".formatted(
                        s.getSede().toLowerCase(), s.getVariable(), s.getId());
                mqtt.publicar(topic, json.writeValueAsString(payload));
            } catch (Exception e) {
                log.error("[SIMULADOR] No se pudo publicar {}: {}", s.getId(), e.getMessage());
            }
        }
    }

    /**
     * Genera un valor creíble por variable:
     * - temperatura: caminata aleatoria alrededor de la base, +/- 2 °C (precision Tabla 1)
     * - calidad-aire: base 400-650 ppm con picos ocasionales que cruzan 900
     * - consumo electrico/agua: valores flotantes estables
     */
    private double medir(Sensor s) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        double base = s.getValorBase() != null ? s.getValorBase()
                : valorBasePorDefecto(s.getVariable());
        return switch (s.getVariable()) {
            case "temperatura" -> redondear(base + rnd.nextDouble(-2, 2), 1);
            case "calidad-aire" -> {
                double pico = rnd.nextDouble() < 0.08 ? rnd.nextDouble(400, 550) : 0;
                yield redondear(Math.max(380, Math.min(1200, base + pico + rnd.nextDouble(-60, 60))), 0);
            }
            case "consumo-electrico" -> redondear(base + rnd.nextDouble(-8, 8), 1);
            case "consumo-agua" -> redondear(Math.max(0, base + rnd.nextDouble(-0.4, 0.4)), 2);
            default -> base;
        };
    }

    private double redondear(double v, int decimales) {
        double p = Math.pow(10, decimales);
        return Math.round(v * p) / p;
    }

    /** Apaga un sensor (usado por el panel de control / la ruleta) */
    public void apagar(String sensorId) {
        if (!sensores.existsById(sensorId)) {
            log.warn("[SIMULADOR] Orden de apagar para sensor inexistente: {}", sensorId);
            return;
        }
        apagados.put(sensorId, true);
        log.warn("[SIMULADOR] Sensor {} APAGADO por orden del panel", sensorId);
    }

    /** Reactiva un sensor apagado */
    public void restaurar(String sensorId) {
        if (apagados.remove(sensorId) != null) {
            log.info("[SIMULADOR] Sensor {} restaurado", sensorId);
        }
    }

    /** Limpia el mapa al eliminar un sensor con el CRUD */
    public void olvidar(String sensorId) {
        apagados.remove(sensorId);
    }

    public Map<String, Boolean> estadoApagados() {
        return new LinkedHashMap<>(apagados);
    }

    private record SensorDef(String id, String variable, String sede, String salon, double base) {
    }
}
