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
import com.campus.monitoring.persistencia.PersistenciaService;
import com.campus.monitoring.persistencia.Sensor;
import com.campus.monitoring.persistencia.SensorRepository;
import com.campus.monitoring.procesamiento.ProcesamientoService;
import com.campus.monitoring.recepcion.RecepcionService;
import com.campus.monitoring.sensor.SensorSimulator;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Ruleta de fallas del panel de control.
 *
 * POST /api/demo/panic     -> elige AL AZAR la victima: un sensor del
 *                             catalogo o cualquier servicio del flujo
 *                             (recepcion, procesamiento, persistencia/BD,
 *                             broker MQTT) y lo deja fuera de servicio.
 * POST /api/demo/restaurar -> reactiva todo lo caido y drena las colas.
 * GET  /api/demo/estado    -> que esta caido, desde cuando y cuanta cola
 *                             se acumulo (para el banner del tablero).
 *
 * Cada caida se simula con un flag en el propio servicio: mientras este
 * activo, el servicio lanza ComponenteCaidoException (o el bus no confirma
 * publicaciones) y los mensajes quedan PENDIENTES en cola sin perderse.
 * Una sola falla activa a la vez.
 */
@RestController
@RequestMapping("/api/demo")
public class DemoController {

    private static final Logger log = LoggerFactory.getLogger(DemoController.class);

    /** Sin falla activa */
    private static final String NINGUNA = null;

    private final SensorSimulator simulador;
    private final RecepcionService recepcion;
    private final ProcesamientoService procesamiento;
    private final PersistenciaService persistencia;
    private final SensorRepository sensores;
    private final ColaMensajesRepository colas;
    private final MqttBus mqtt;
    private final ObjectMapper json = new ObjectMapper();

    /**
     * Tipo de la falla activa:
     * "SENSOR" | "RECEPCION" | "PROCESAMIENTO" | "PERSISTENCIA" | "MQTT"
     */
    private volatile String tipoCaido = NINGUNA;
    /** Id del sensor caido o nombre del servicio */
    private volatile String victima = NINGUNA;
    private volatile Instant desde = null;

    public DemoController(SensorSimulator simulador, RecepcionService recepcion,
                          ProcesamientoService procesamiento, PersistenciaService persistencia,
                          SensorRepository sensores, ColaMensajesRepository colas, MqttBus mqtt) {
        this.simulador = simulador;
        this.recepcion = recepcion;
        this.procesamiento = procesamiento;
        this.persistencia = persistencia;
        this.sensores = sensores;
        this.colas = colas;
        this.mqtt = mqtt;
    }

    /**
     * La ruleta: sortea entre un sensor del catalogo y cualquier servicio
     * del flujo de datos. Si ya hay una falla activa, 409.
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

        // La ruleta: un slot por sensor candidato + un slot por servicio.
        List<String> servicios = List.of("RECEPCION", "PROCESAMIENTO", "PERSISTENCIA", "MQTT");
        int slots = candidatos.size() + servicios.size();
        int tiro = ThreadLocalRandom.current().nextInt(slots);

        Map<String, Object> r = new LinkedHashMap<>();
        if (tiro < candidatos.size()) {
            Sensor victimaSensor = candidatos.get(tiro);
            publicarOrden("apagar", victimaSensor.getId());
            tipoCaido = "SENSOR";
            victima = victimaSensor.getId();
            r.put("tipo", "SENSOR");
            r.put("victima", victima);
            r.put("detalle", victimaSensor.getVariable() + " · " + victimaSensor.getSede()
                    + " · " + victimaSensor.getSalon());
            log.warn("[DEMO] Ruleta -> SENSOR {}", victima);
        } else {
            String servicio = servicios.get(tiro - candidatos.size());
            switch (servicio) {
                case "RECEPCION" -> {
                    recepcion.caer();
                    victima = "Recepcion";
                    r.put("detalle", "Los mensajes no se validan: quedan en cola hasta restaurar");
                }
                case "PROCESAMIENTO" -> {
                    procesamiento.caer();
                    victima = "Procesamiento";
                    r.put("detalle", "Las mediciones se acumulan en cola hasta restaurar");
                }
                case "PERSISTENCIA" -> {
                    persistencia.caer();
                    victima = "Base de datos";
                    r.put("detalle", "Nada se guarda: los mensajes esperan en cola hasta restaurar");
                }
                case "MQTT" -> {
                    mqtt.caerBroker();
                    victima = "Broker MQTT";
                    r.put("detalle", "Nada fluye por el bus: la cola de salida crece hasta restaurar");
                }
                default -> throw new IllegalStateException("servicio desconocido: " + servicio);
            }
            tipoCaido = servicio;
            r.put("tipo", servicio);
            r.put("victima", victima);
            log.warn("[DEMO] Ruleta -> {} ({})", servicio, victima);
        }
        desde = Instant.now();
        r.put("desde", desde.toString());
        return r;
    }

    /** Reactiva todo lo caido y drena las colas pendientes al instante */
    @PostMapping("/restaurar")
    public Map<String, Object> restaurar() {
        String tipoAnterior = tipoCaido;
        String victimaAnterior = victima;

        publicarOrden("restaurar", null);   // sensores (Pub/Sus con el simulador)
        recepcion.levantar();
        procesamiento.levantar();
        persistencia.levantar();
        mqtt.levantarBroker();
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
