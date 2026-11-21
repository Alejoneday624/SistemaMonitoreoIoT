package com.campus.monitoring.api;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.campus.monitoring.config.MqttBus;
import com.campus.monitoring.persistencia.Sensor;
import com.campus.monitoring.persistencia.SensorRepository;
import com.campus.monitoring.sensor.SensorSimulator;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * CRUD de sensores (modificabilidad): agregar, modificar y eliminar un
 * sensor SIN tocar codigo ni reiniciar.
 *
 * El simulador lee el catalogo de la BD en cada ciclo, asi que cualquier
 * cambio aqui se refleja en la proxima publicacion:
 *   POST   /api/sensores           -> crear (el simulador empieza a medirlo)
 *   PUT    /api/sensores/{id}      -> modificar (variable, sede, salon, base)
 *   DELETE /api/sensores/{id}      -> eliminar (deja de publicarse)
 *   POST   /api/sensores/{id}/apagar|encender -> toggle individual (T6/HDU-05)
 *
 * APAGAR/ENCENDER VIA MQTT (T6 — gestion de comunicacion MQTT):
 * el controlador NO toca al simulador: publica la orden en
 * `control/simulador/orden` (QoS 1) y el simulador la interpreta. Si el
 * broker o el backend estan caidos, la orden queda en la cola durable de
 * salida (outbox) y se aplica cuando el sistema se recupera.
 *
 * GET /api/sensores (estado + vigencia) sigue en PanelApiController.
 */
@RestController
@RequestMapping("/api/sensores")
public class SensoresController {

    private static final Logger log = LoggerFactory.getLogger(SensoresController.class);

    /** Topic de ordenes para el simulador (Tabla 33/35 del documento) */
    public static final String TOPIC_ORDENES = "control/simulador/orden";

    /** Variables soportadas por el generador del simulador (Tabla 1) */
    private static final Set<String> VARIABLES = Set.of(
            "temperatura", "calidad-aire", "consumo-electrico", "consumo-agua");

    private static final Set<String> SEDES = Set.of("Sur", "Federman");

    private final SensorRepository sensores;
    private final SensorSimulator simulador;
    private final MqttBus mqtt;
    private final ObjectMapper json = new ObjectMapper();

    public SensoresController(SensorRepository sensores, SensorSimulator simulador, MqttBus mqtt) {
        this.sensores = sensores;
        this.simulador = simulador;
        this.mqtt = mqtt;
    }

    /** Crea un sensor nuevo y lo registra para que el simulador lo mida */
    @PostMapping
    public Sensor crear(@RequestBody Sensor pedido) {
        String id = normalizar(pedido.getId(), "id");
        if (sensores.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Ya existe un sensor con id '" + id + "'");
        }
        Sensor s = new Sensor(id,
                validarVariable(pedido.getVariable()),
                validarSede(pedido.getSede()),
                recortar(pedido.getSalon(), 80, "salon"),
                pedido.getFrecuenciaSegundos() == null ? 5 : pedido.getFrecuenciaSegundos());
        s.setValorBase(pedido.getValorBase());
        Sensor guardado = sensores.save(s);
        log("[CREAR]", guardado);
        return guardado;
    }

    /** Modifica un sensor existente; el simulador usa los nuevos datos en el proximo ciclo */
    @PutMapping("/{id}")
    public Sensor modificar(@PathVariable String id, @RequestBody Sensor pedido) {
        Sensor s = sensores.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No existe el sensor '" + id + "'"));
        // El id NO se cambia (es la clave de mediciones y de las graficas)
        s.setVariable(validarVariable(pedido.getVariable()));
        s.setSede(validarSede(pedido.getSede()));
        s.setSalon(recortar(pedido.getSalon(), 80, "salon"));
        if (pedido.getValorBase() != null) {
            s.setValorBase(pedido.getValorBase());
        }
        if (pedido.getFrecuenciaSegundos() != null && pedido.getFrecuenciaSegundos() > 0) {
            s.setFrecuenciaSegundos(pedido.getFrecuenciaSegundos());
        }
        Sensor guardado = sensores.save(s);
        log("[MODIFICAR]", guardado);
        return guardado;
    }

    /**
     * Elimina un sensor: deja de publicarse en el proximo ciclo. Sus
     * mediciones historicas se conservan (no hay FK: son datos, no config).
     */
    @DeleteMapping("/{id}")
    public Map<String, Object> eliminar(@PathVariable String id) {
        if (!sensores.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "No existe el sensor '" + id + "'");
        }
        sensores.deleteById(id);
        simulador.olvidar(id); // si estaba apagado, no queda fantasma
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("eliminado", id);
        r.put("medicionesConservadas", true);
        org.slf4j.LoggerFactory.getLogger(SensoresController.class)
                .info("[SENSORES] [ELIMINAR] {} (mediciones conservadas)", id);
        return r;
    }

    /**
     * Apaga un sensor individualmente (T6 — HDU-05). La orden viaja por MQTT:
     * el simulador es el unico que cambia el estado del sensor.
     */
    @PostMapping("/{id}/apagar")
    public Map<String, Object> apagar(@PathVariable String id) {
        exigir(id);
        publicarOrden("apagar", id);
        return estadoSensor(id, true);
    }

    /** Reactiva un sensor apagado (misma ruta MQTT que apagar) */
    @PostMapping("/{id}/encender")
    public Map<String, Object> encender(@PathVariable String id) {
        exigir(id);
        publicarOrden("restaurar", id);
        return estadoSensor(id, false);
    }

    /** Estado inmediato de la orden + salud del canal MQTT */
    private Map<String, Object> estadoSensor(String id, boolean apagado) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", id);
        r.put("apagadoSolicitado", apagado);
        r.put("via", "MQTT " + TOPIC_ORDENES);
        r.put("brokerConectado", mqtt.emisorConectado());
        r.put("brokerCaidoDemo", mqtt.estaCaido());
        r.put("confirmacion", mqtt.estaCaido()
                ? "orden guardada en la cola de salida; se aplicara al restaurar el broker"
                : "orden publicada con QoS 1; el simulador la aplica en el proximo ciclo");
        r.put("estado", apagado ? "APAGANDO" : "ENCENDIENDO");
        r.put("actualizadoEn", Instant.now().toString());
        return r;
    }

    /** Publica la orden en control/simulador/orden (el simulador la interpreta) */
    private void publicarOrden(String accion, String sensorId) {
        try {
            Map<String, Object> orden = new LinkedHashMap<>();
            orden.put("accion", accion);
            orden.put("sensorId", sensorId);
            orden.put("origen", "api-rest");
            orden.put("fecha", Instant.now().toString());
            mqtt.publicar(TOPIC_ORDENES, json.writeValueAsString(orden));
        } catch (Exception e) {
            log.error("[SENSORES] No se pudo publicar la orden {} para {}: {}",
                    accion, sensorId, e.getMessage());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "No se pudo publicar la orden MQTT: " + e.getMessage());
        }
    }

    private void exigir(String id) {
        if (!sensores.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "No existe el sensor '" + id + "'");
        }
    }

    // ------------------------------------------------------------ validaciones

    private String validarVariable(String v) {
        String valor = normalizar(v, "variable");
        if (!VARIABLES.contains(valor)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Variable '" + valor + "' no soportada. Use una de: " + VARIABLES);
        }
        return valor;
    }

    private String validarSede(String v) {
        String valor = normalizar(v, "sede");
        if (!SEDES.contains(valor)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Sede '" + valor + "' invalida. Use una de: " + SEDES);
        }
        return valor;
    }

    private String normalizar(String valor, String campo) {
        if (valor == null || valor.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El campo '" + campo + "' es obligatorio");
        }
        String limpio = valor.trim();
        if ("id".equals(campo) && limpio.length() > 40) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El id no puede superar 40 caracteres");
        }
        return limpio;
    }

    private String recortar(String valor, int max, String campo) {
        if (valor == null || valor.isBlank()) {
            return null;
        }
        return valor.trim().substring(0, Math.min(valor.trim().length(), max));
    }

    private void log(String accion, Sensor s) {
        org.slf4j.LoggerFactory.getLogger(SensoresController.class)
                .info("[SENSORES] {} {} ({} · {} · base={})",
                        accion, s.getId(), s.getVariable(), s.getSede(), s.getValorBase());
    }
}
