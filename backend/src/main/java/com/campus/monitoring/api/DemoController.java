package com.campus.monitoring.api;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Ruleta de fallas del panel de control (T4 — simulacion de fallas).
 *
 * POST /api/demo/panic     -> elige AL AZAR un sensor encendido o cualquier
 *                             servicio del flujo (recepcion, procesamiento,
 *                             persistencia/BD, broker MQTT) y lo deja fuera de
 *                             servicio. Admite ?tipo=SENSOR|RECEPCION|
 *                             PROCESAMIENTO|PERSISTENCIA|MQTT para forzar la
 *                             victima durante una demostracion.
 * POST /api/demo/restaurar -> reactiva la victima y drena las colas.
 * GET  /api/demo/estado    -> que esta caido, desde cuando, cuanta cola se
 *                             acumulo y el detalle del sensor si aplica.
 *
 * El sensor se deja de publicar por MQTT (control/simulador/orden, accion
 * "fallar"): la API no toca directamente al simulador. A diferencia del
 * apagado manual (T6, accion "apagar"), el sensor sigue "encendido" para que
 * el heartbeat lo detecte y publique SENSOR_INACTIVO (E1/HDU-04).
 *
 * Cada caida de servicio se simula con un flag en el propio servicio: mientras
 * este activo, el servicio lanza ComponenteCaidoException (o el bus no confirma
 * publicaciones) y los mensajes quedan PENDIENTES en cola sin perderse.
 * Una sola falla activa a la vez.
 */
@RestController
@RequestMapping("/api/demo")
public class DemoController {

    private static final Logger log = LoggerFactory.getLogger(DemoController.class);

    /** Sin falla activa */
    private static final String NINGUNA = null;

    private static final List<String> SERVICIOS = List.of(
            "SENSOR", "RECEPCION", "PROCESAMIENTO", "PERSISTENCIA", "MQTT");

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
    /** Nombre del servicio caido (o id del sensor) */
    private volatile String victima = NINGUNA;
    private volatile Instant desde = null;

    public DemoController(RecepcionService recepcion,
                          ProcesamientoService procesamiento, PersistenciaService persistencia,
                          SensorRepository sensores,
                          ColaMensajesRepository colas, MqttBus mqtt) {
        this.recepcion = recepcion;
        this.procesamiento = procesamiento;
        this.persistencia = persistencia;
        this.sensores = sensores;
        this.colas = colas;
        this.mqtt = mqtt;
    }

    /**
     * La ruleta: sortea AL AZAR entre un sensor encendido y los servicios del
     * flujo de datos. Con ?tipo=... se puede forzar la victima.
     * Si ya hay una falla activa, 409.
     */
    @PostMapping("/panic")
    public Map<String, Object> panic(@RequestParam(required = false) String tipo) {
        if (tipoCaido != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Ya hay una falla activa (" + tipoCaido + ": " + victima
                            + "). Restaura antes de girar la ruleta otra vez.");
        }

        String servicio = elegirVictima(tipo);

        Map<String, Object> r = new LinkedHashMap<>();
        switch (servicio) {
            case "SENSOR" -> {
                Sensor sensor = elegirSensorEncendido();
                publicarOrden("fallar", sensor.getId());
                victima = sensor.getId();
                r.put("detalle", "Orden MQTT enviada al simulador: el sensor deja de publicar "
                        + "y el heartbeat lo marcara INACTIVO tras el umbral");
                r.put("ubicacion", ubicacion(sensor));
                r.put("estadoSensor", "FALLANDO");
            }
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
        desde = Instant.now();
        r.put("desde", desde.toString());
        log.warn("[DEMO] Ruleta -> {} ({})", servicio, victima);
        return r;
    }

    /** Valida el tipo forzado o sortea uno al azar entre todos los slots */
    private String elegirVictima(String tipoForzado) {
        if (tipoForzado == null || tipoForzado.isBlank()) {
            return SERVICIOS.get(ThreadLocalRandom.current().nextInt(SERVICIOS.size()));
        }
        String normalizado = tipoForzado.trim().toUpperCase(Locale.ROOT);
        if (!SERVICIOS.contains(normalizado)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Tipo de falla invalido: " + tipoForzado + ". Use uno de: " + SERVICIOS);
        }
        return normalizado;
    }

    /** Un sensor encendido al azar (si todos estan apagados no hay nada que tumbar) */
    private Sensor elegirSensorEncendido() {
        List<Sensor> encendidos = sensores.findAll().stream()
                .filter(Sensor::estaEncendido)
                .toList();
        if (encendidos.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "No hay sensores encendidos: encienda uno antes de girar la ruleta");
        }
        return encendidos.get(ThreadLocalRandom.current().nextInt(encendidos.size()));
    }

    /**
     * Reactiva la victima y drena las colas pendientes al instante.
     * Si la victima era un sensor, solo se enciende ESE sensor: los que el
     * administrador apago a mano se respetan.
     */
    @PostMapping("/restaurar")
    public Map<String, Object> restaurar() {
        String tipoAnterior = tipoCaido;
        String victimaAnterior = victima;

        if ("SENSOR".equals(tipoAnterior) && victimaAnterior != null) {
            publicarOrden("restaurar", victimaAnterior);
        }
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
        if ("SENSOR".equals(tipoCaido) && victima != null) {
            sensores.findById(victima).ifPresent(s -> {
                r.put("sensorId", s.getId());
                r.put("ubicacion", ubicacion(s));
                r.put("fallaSimulada", true);
                r.put("encendido", s.estaEncendido());
                r.put("estadoSensor", s.getEstado());
                r.put("ultimaMedicionEn", s.getUltimaMedicionEn());
                r.put("segundosSinDatos", s.getUltimaMedicionEn() == null ? null
                        : Duration.between(s.getUltimaMedicionEn(), Instant.now()).getSeconds());
            });
        }
        return r;
    }

    private static String ubicacion(Sensor s) {
        return s.getSede() + (s.getSalon() == null || s.getSalon().isBlank()
                ? "" : " · " + s.getSalon());
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
