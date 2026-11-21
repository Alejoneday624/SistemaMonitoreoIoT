package com.campus.monitoring.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.campus.monitoring.config.MqttBus;

/**
 * Gestion de la comunicacion MQTT (Tablas 33/35 del documento).
 *
 * GET /api/mqtt/estado -> salud del canal Pub/Sus en tiempo real:
 *   - broker configurado y si el cliente emisor esta conectado;
 *   - clientes (emisor y suscriptores) con su estado de conexion;
 *   - suscripciones activas y la cola durable (inbox) de cada una;
 *   - mensajes esperando PUBACK en la cola de salida (outbox);
 *   - mapa de topics: quien publica, quien escucha y para que.
 *
 * Sirve para demostrar que la caida de un componente no rompe el flujo:
 * las ordenes de apagar/encender y las mediciones esperan en la outbox y se
 * entregan en orden (QoS 1 + sesiones persistentes) al restaurar.
 */
@RestController
@RequestMapping("/api/mqtt")
public class MqttController {

    private final MqttBus mqtt;

    public MqttController(MqttBus mqtt) {
        this.mqtt = mqtt;
    }

    @GetMapping("/estado")
    public Map<String, Object> estado() {
        Map<String, Object> broker = new LinkedHashMap<>();
        broker.put("url", mqtt.url());
        broker.put("clienteEmisorConectado", mqtt.emisorConectado());
        broker.put("caidoPorDemo", mqtt.estaCaido());
        broker.put("colaSalidaPendiente", mqtt.pendientesSalida());
        broker.put("qos", 1);
        broker.put("sesionesPersistentes", true);

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("broker", broker);
        r.put("clientes", mqtt.estadoClientes());
        r.put("suscripciones", mqtt.estadoSuscripciones());
        r.put("topics", topics());
        return r;
    }

    /** Mapa de topics del prototipo (coincide con el README y el documento) */
    private List<Map<String, Object>> topics() {
        List<Map<String, Object>> salida = new ArrayList<>();
        salida.add(topic("campus/{sede}/{variable}/{sensorId}",
                "Simulador de sensores", "Recepción", "Medición cruda cada 5 s"));
        salida.add(topic("interno/validadas",
                "Recepción", "Procesamiento", "Mediciones con formato válido"));
        salida.add(topic("interno/procesadas",
                "Procesamiento", "Persistencia", "Mediciones listas para guardar"));
        salida.add(topic("interno/alertas",
                "Monitoreo (heartbeat)", "Persistencia", "SENSOR_INACTIVO / SENSOR_RECUPERADO"));
        salida.add(topic("interno/logs",
                "Recepción / Procesamiento / Persistencia", "Persistencia", "Errores y eventos"));
        salida.add(topic("control/simulador/#",
                "API REST (T6/ruleta T4)", "Simulador", "Órdenes apagar / encender"));
        return salida;
    }

    private static Map<String, Object> topic(String topic, String publica, String escucha, String uso) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("topic", topic);
        m.put("publica", publica);
        m.put("escucha", escucha);
        m.put("uso", uso);
        return m;
    }
}
