package com.campus.monitoring.api;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.campus.monitoring.cola.ColaMensajes;
import com.campus.monitoring.cola.ColaMensajesRepository;
import com.campus.monitoring.config.MqttBus;
import com.campus.monitoring.persistencia.Sensor;
import com.campus.monitoring.persistencia.SensorRepository;
import com.campus.monitoring.procesamiento.ProcesamientoService;
import com.campus.monitoring.sensor.SensorSimulator;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Demo de fallas (E1/E11) — la "ruleta" del panel de control.
 *
 * POST /api/demo/panic     -> elige AL AZAR la victima: un sensor del
 *                             catalogo o el componente interno de
 *                             procesamiento, y la apaga.
 * POST /api/demo/restaurar -> reactiva todo lo apagado y drena la cola.
 * GET  /api/demo/estado    -> que esta caido, desde cuando y cuanta cola
 *                             se acumulo (para el banner del tablero).
 *
 * La orden al simulador viaja por MQTT (Pub/Sus puro, topic
 * control/simulador/#): la API no toca el simulador directamente.
 */
@RestController
@RequestMapping("/api/demo")
public class DemoController {

    private static final Logger log = LoggerFactory.getLogger(DemoController.class);

    /** Una sola falla activa a la vez (la demo se lee mas clara) */
    private static final String NINGUNA = null;

    private final SensorSimulator simulador;
    private final ProcesamientoService procesamiento;
    private final SensorRepository sensores;
    private final ColaMensajesRepository colas;
    private final MqttBus mqtt;
    private final ObjectMapper json = new ObjectMapper();

    /** Tipo de la falla activa: "SENSOR" | "COMPONENTE" | null */
    private volatile String tipoCaido = NINGUNA;
    /** Id del sensor caido o nombre del componente */
    private volatile String victima = NINGUNA;
    private volatile Instant desde = null;

    public DemoController(SensorSimulator simulador, ProcesamientoService procesamiento,
                          SensorRepository sensores, ColaMensajesRepository colas, MqttBus mqtt) {
        this.simulador = simulador;
        this.procesamiento = procesamiento;
        this.sensores = sensores;
        this.colas = colas;
        this.mqtt = mqtt;
    }

    /**
     * La ruleta: 50/50 entre apagar un sensor del catalogo y caer el
     * componente de procesamiento. Si ya hay una falla activa, 400.
     */
    @PostMapping("/panic")
    public Map<String, Object> panic() {
        if (tipoCaido != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Ya hay una falla activa (" + tipoCaido + ": " + victima
                            + "). Restaura antes de girar la ruleta otra vez.");
        }
        List<Sensor> candidatos = sensores.findAll().stream()
                .filter(s -> !simulador.estadoApagados().containsKey(s.getId()))
                .toList();
        boolean elegirSensor = !candidatos.isEmpty()
                && ThreadLocalRandom.current().nextBoolean();

        Map<String, Object> r = new LinkedHashMap<>();
        if (elegirSensor) {
            Sensor victimaSensor = candidatos.get(ThreadLocalRandom.current().nextInt(candidatos.size()));
            publicarOrden("apagar", victimaSensor.getId());
            tipoCaido = "SENSOR";
            victima = victimaSensor.getId();
            r.put("tipo", "SENSOR");
            r.put("victima", victimaSensor.getId());
            r.put("detalle", victimaSensor.getVariable() + " · " + victimaSensor.getSede()
                    + " · " + victimaSensor.getSalon());
            log.warn("[DEMO] Ruleta -> SENSOR {}", victima);
        } else {
            procesamiento.caer();
            tipoCaido = "COMPONENTE";
            victima = "Procesamiento";
            r.put("tipo", "COMPONENTE");
            r.put("victima", victima);
            r.put("detalle", "Las mediciones se acumulan en cola hasta restaurar");
            log.warn("[DEMO] Ruleta -> COMPONENTE procesamiento");
        }
        desde = Instant.now();
        r.put("desde", desde.toString());
        return r;
    }

    /** Reactiva sensor y componente, y drena la cola pendiente al instante */
    @PostMapping("/restaurar")
    public Map<String, Object> restaurar() {
        String tipoAnterior = tipoCaido;
        String victimaAnterior = victima;

        publicarOrden("restaurar", null);   // todas (Pub/Sus con el simulador)
        procesamiento.levantar();
        mqtt.drenarColas();                 // no esperar al tick: vacia ya

        tipoCaido = NINGUNA;
        victima = NINGUNA;
        desde = null;

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("restaurado", true);
        r.put("anteriorTipo", tipoAnterior);
        r.put("anteriorVictima", victimaAnterior);
        r.put("colaPendiente", totalCola(ColaMensajes.PENDIENTE));
        log.info("[DEMO] Restaurado (era {} {}); cola pendiente={}",
                tipoAnterior, victimaAnterior, r.get("colaPendiente"));
        return r;
    }

    /** Estado para el banner: que caido, desde cuando y cuanta cola hay */
    @GetMapping("/estado")
    public Map<String, Object> estado() {
        Map<String, Object> r = new LinkedHashMap<>();
        boolean caido = tipoCaido != null;
        r.put("caido", caido);
        r.put("tipo", tipoCaido);
        r.put("victima", victima);
        r.put("desde", desde == null ? null : desde.toString());
        r.put("segundosCaido", desde == null ? null
                : Duration.between(desde, Instant.now()).getSeconds());
        r.put("colaPendiente", totalCola(ColaMensajes.PENDIENTE));
        r.put("colaFallidos", totalCola(ColaMensajes.FALLIDO));
        r.put("porCola", resumenColas());
        return r;
    }

    /** Total de mensajes en un estado dado (todas las colas juntas) */
    private long totalCola(String estado) {
        return colas.resumenPorCola().stream()
                .filter(f -> estado.equals(f[1]))
                .mapToLong(f -> (Long) f[2])
                .sum();
    }

    /** Detalle por cola: [{cola, estado, total}, ...] */
    private List<Map<String, Object>> resumenColas() {
        List<Map<String, Object>> salida = new ArrayList<>();
        for (Object[] f : colas.resumenPorCola()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("cola", f[0]);
            m.put("estado", f[1]);
            m.put("total", f[2]);
            salida.add(m);
        }
        return salida;
    }

    /** Publica la orden de falla al simulador (Pub/Sus, control/simulador/#) */
    private void publicarOrden(String accion, String sensorId) {
        try {
            Map<String, Object> orden = new LinkedHashMap<>();
            orden.put("accion", accion);
            if (sensorId != null) {
                orden.put("sensorId", sensorId);
            }
            mqtt.publicar("control/simulador/orden", json.writeValueAsString(orden));
        } catch (Exception e) {
            log.error("[DEMO] No se pudo publicar la orden {}: {}", accion, e.getMessage());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "No se pudo publicar la orden MQTT: " + e.getMessage());
        }
    }
}
