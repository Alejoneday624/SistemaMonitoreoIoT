package com.campus.monitoring.monitoreo;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.campus.monitoring.config.MqttBus;
import com.campus.monitoring.persistencia.Sensor;
import com.campus.monitoring.persistencia.SensorRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Servicio de monitoreo — HEARTBEAT (T3 · HDU-04 · escenario E1).
 *
 * No hay ping independiente: el propio flujo de mediciones es el latido.
 * Cada vez que Persistencia guarda una medicion actualiza
 * `ultimaMedicionEn` y marca el sensor ACTIVO (incluido el caso de un
 * duplicado rechazado por la restriccion unica). Este servicio revisa
 * periodicamente esa marca y:
 *
 *  - si un sensor encendido lleva mas de `heartbeat.segundos` (120 s por
 *    defecto) sin una medicion nueva, lo marca INACTIVO y publica una alerta
 *    en `interno/alertas` (una sola vez por caida);
 *  - si vuelve a transmitir, lo marca ACTIVO y publica SENSOR_RECUPERADO;
 *  - si el administrador apago el sensor (T6), lo marca APAGADO y NO alerta.
 *
 * La referencia temporal es la fecha REGISTRADA POR EL SENSOR y nunca
 * retrocede: Persistencia ya ignora mensajes viejos reentregados por QoS 1.
 * Para no generar una avalancha de alertas al arrancar (la BD conserva
 * ultimas mediciones antiguas), los primeros `heartbeat.arranque-ms`
 * milisegundos solo se observa, no se alerta.
 *
 * Las alertas se publican como JSON con tipo SENSOR_INACTIVO y los datos de
 * ubicacion que exige el criterio de aceptacion (sensor, ubicacion, fecha y
 * hora de deteccion). Persistencia las archiva en la tabla de errores y el
 * tablero las muestra en el banner/registro.
 */
@Component
public class MonitoreoService {

    private static final Logger log = LoggerFactory.getLogger(MonitoreoService.class);

    /** Topic Pub/Sus de alertas (evaluador de umbrales y heartbeat) */
    public static final String TOPIC_ALERTAS = "interno/alertas";

    public static final String ACTIVO = "ACTIVO";
    public static final String INACTIVO = "INACTIVO";
    public static final String APAGADO = "APAGADO";

    private final SensorRepository sensores;
    private final MqttBus mqtt;
    private final ObjectMapper json = new ObjectMapper();

    private final long heartbeatSegundos;
    private final long graciaArranqueMs;
    private final Instant iniciadoEn = Instant.now();

    /** Sensores a los que ya se les publico la alerta de inactividad */
    private final Set<String> alertados = ConcurrentHashMap.newKeySet();

    private volatile Instant ultimaComprobacion;
    private volatile long comprobaciones;

    public MonitoreoService(SensorRepository sensores,
                            MqttBus mqtt,
                            @Value("${heartbeat.segundos:120}") long heartbeatSegundos,
                            @Value("${heartbeat.arranque-ms:30000}") long graciaArranqueMs) {
        this.sensores = sensores;
        this.mqtt = mqtt;
        this.heartbeatSegundos = heartbeatSegundos;
        this.graciaArranqueMs = graciaArranqueMs;
    }

    /**
     * Tick del monitor. Corre cada 10 s (configurable) y revisa el catalogo
     * completo: con 12 sensores es una consulta trivial.
     */
    @Scheduled(fixedDelayString = "${heartbeat.comprobacion-ms:10000}",
            initialDelayString = "${heartbeat.comprobacion-ms:10000}")
    public void verificarSensores() {
        Instant ahora = Instant.now();
        comprobaciones++;
        ultimaComprobacion = ahora;

        // Gracia de arranque: el simulador publica en <=5 s; no alertar por
        // datos historicos de la BD antes de que el flujo se estabilice.
        if (Duration.between(iniciadoEn, ahora).toMillis() < graciaArranqueMs) {
            return;
        }

        for (Sensor s : sensores.findAll()) {
            try {
                revisar(s, ahora);
            } catch (Exception e) {
                // Un sensor con problemas no debe detener la revision del resto
                log.warn("[MONITOREO] No se pudo revisar {}: {}", s.getId(), e.getMessage());
            }
        }
    }

    private void revisar(Sensor s, Instant ahora) {
        if (!s.estaEncendido()) {
            if (!APAGADO.equals(s.getEstado())) {
                sensores.actualizarEstado(s.getId(), APAGADO);
            }
            alertados.remove(s.getId());
            return;
        }

        Instant referencia = ultimaReferencia(s);
        if (referencia == null) {
            // Sin ninguna marca temporal: se toma este instante como inicio
            s.setRegistradoEn(ahora);
            s.setEstadoCambiadoEn(ahora);
            sensores.save(s);
            return;
        }

        long sinDatos = Math.max(0, Duration.between(referencia, ahora).getSeconds());

        if (sinDatos > heartbeatSegundos) {
            if (!INACTIVO.equals(s.getEstado())) {
                sensores.actualizarEstado(s.getId(), INACTIVO);
                log.warn("[MONITOREO] {} -> INACTIVO: {} s sin datos (umbral {} s, ubicacion {})",
                        s.getId(), sinDatos, heartbeatSegundos, ubicacion(s));
            }
            if (alertados.add(s.getId())) {
                publicarAlerta(s, referencia, sinDatos, ahora);
            }
            return;
        }

        // Llego un latido: si estaba inactivo se recupera y se avisa una vez
        if (INACTIVO.equals(s.getEstado())) {
            sensores.actualizarEstado(s.getId(), ACTIVO);
            log.info("[MONITOREO] {} -> ACTIVO (latido recuperado, {} s)", s.getId(), sinDatos);
        }
        if (alertados.remove(s.getId())) {
            publicarRecuperacion(s, ahora);
        }
    }

    /**
     * Referencia del heartbeat: la fecha de la ultima medicion o, si el
     * sensor acaba de registrarse/encenderse, ese instante (gracia).
     */
    private Instant ultimaReferencia(Sensor s) {
        Instant ref = s.getUltimaMedicionEn();
        if (s.getEstadoCambiadoEn() != null
                && (ref == null || s.getEstadoCambiadoEn().isAfter(ref))) {
            ref = s.getEstadoCambiadoEn();
        }
        if (s.getRegistradoEn() != null
                && (ref == null || s.getRegistradoEn().isAfter(ref))) {
            ref = s.getRegistradoEn();
        }
        return ref;
    }

    private void publicarAlerta(Sensor s, Instant referencia, long sinDatos, Instant ahora) {
        Map<String, Object> alerta = new LinkedHashMap<>();
        alerta.put("tipo", "SENSOR_INACTIVO");
        alerta.put("sensorId", s.getId());
        alerta.put("variable", s.getVariable());
        alerta.put("sede", s.getSede());
        alerta.put("salon", s.getSalon());
        alerta.put("ubicacion", ubicacion(s));
        alerta.put("fecha", ahora.toString());
        alerta.put("ultimaMedicionEn", referencia.toString());
        alerta.put("segundosSinDatos", sinDatos);
        alerta.put("umbralSegundos", heartbeatSegundos);
        alerta.put("destinatario", "mantenimiento");
        alerta.put("descripcion", "Sensor " + s.getId() + " (" + ubicacion(s)
                + ") sin transmitir datos desde " + referencia
                + " — " + sinDatos + " s sin heartbeat (umbral " + heartbeatSegundos + " s)");
        try {
            mqtt.publicar(TOPIC_ALERTAS, json.writeValueAsString(alerta));
            log.warn("[MONITOREO] ALERTA SENSOR_INACTIVO publicada: {}", s.getId());
        } catch (Exception e) {
            log.error("[MONITOREO] No se pudo publicar la alerta de {}: {}",
                    s.getId(), e.getMessage());
        }
    }

    private void publicarRecuperacion(Sensor s, Instant ahora) {
        Map<String, Object> evento = new LinkedHashMap<>();
        evento.put("tipo", "SENSOR_RECUPERADO");
        evento.put("sensorId", s.getId());
        evento.put("variable", s.getVariable());
        evento.put("sede", s.getSede());
        evento.put("salon", s.getSalon());
        evento.put("ubicacion", ubicacion(s));
        evento.put("fecha", ahora.toString());
        evento.put("destinatario", "mantenimiento");
        evento.put("descripcion", "Sensor " + s.getId() + " (" + ubicacion(s)
                + ") volvio a transmitir: estado ACTIVO");
        try {
            mqtt.publicar(TOPIC_ALERTAS, json.writeValueAsString(evento));
            log.info("[MONITOREO] Evento SENSOR_RECUPERADO publicado: {}", s.getId());
        } catch (Exception e) {
            log.error("[MONITOREO] No se pudo publicar la recuperacion de {}: {}",
                    s.getId(), e.getMessage());
        }
    }

    private static String ubicacion(Sensor s) {
        return s.getSede() + (s.getSalon() == null || s.getSalon().isBlank()
                ? "" : " · " + s.getSalon());
    }

    // --------------------------------------------------------------- consulta

    /** Resumen del heartbeat para el tablero (/api/monitoreo) */
    public Map<String, Object> resumen() {
        Instant ahora = Instant.now();
        List<Map<String, Object>> lista = new ArrayList<>();
        int activos = 0;
        int inactivos = 0;
        int apagados = 0;

        for (Sensor s : sensores.findAll()) {
            boolean encendido = s.estaEncendido();
            Instant referencia = ultimaReferencia(s);
            Long sinDatos = referencia == null ? null
                    : Math.max(0, Duration.between(referencia, ahora).getSeconds());
            String estado = s.getEstado();
            if (!encendido || APAGADO.equals(estado)) {
                apagados++;
            } else if (INACTIVO.equals(estado)) {
                inactivos++;
            } else {
                activos++;
            }

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.getId());
            m.put("variable", s.getVariable());
            m.put("sede", s.getSede());
            m.put("salon", s.getSalon());
            m.put("ubicacion", ubicacion(s));
            m.put("encendido", encendido);
            m.put("estado", estado);
            m.put("ultimaMedicionEn", s.getUltimaMedicionEn());
            m.put("segundosSinDatos", sinDatos);
            m.put("alertaEnviada", alertados.contains(s.getId()));
            lista.add(m);
        }

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("heartbeatSegundos", heartbeatSegundos);
        r.put("graciaArranqueMs", graciaArranqueMs);
        r.put("comprobaciones", comprobaciones);
        r.put("ultimaComprobacion", ultimaComprobacion);
        r.put("iniciadoEn", iniciadoEn);
        r.put("total", lista.size());
        r.put("activos", activos);
        r.put("inactivos", inactivos);
        r.put("apagados", apagados);
        r.put("sensores", lista);
        return r;
    }
}
