package com.campus.monitoring.config;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Bus MQTT compartido por todos los servicios (Patron Publicar/Suscribir).
 *
 * Decisiones de la arquitectura:
 * - QoS 1 en todas las publicaciones (al menos una vez).
 * - Sesiones NO limpias (cleanSession=false) en los suscriptores: si un
 *   servicio se cae, el broker Mosquitto encola los mensajes pendientes y
 *   se los entrega al reconectar (tacticas E9/E11 del documento).
 * - Reconexion automatica con Paho; la suscripcion se rehace en cada
 *   conexion (connectComplete) para que no dependa del estado del cliente.
 *
 * Regla de hilo (clave para que el tablero no vea los sensores "apagados"):
 * el hilo de callbacks de Paho es UNO solo por cliente y no debe bloquearse.
 * Por eso (1) la suscripcion se lanza fuera de connectComplete y (2) los
 * manejadores de mensajes corren en un ejecutor propio. Si ese hilo se
 * bloquea no se envian los PUBACK, el broker satura la cola del suscriptor,
 * el keepAlive vence y el cliente entra en un bucle de reconexion sin datos.
 */
@Component
public class MqttBus implements InitializingBean, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(MqttBus.class);

    /** Espera maxima de una operacion bloqueante (SUBACK/PUBACK): nada de infinito */
    private static final long OPERACION_MS = 10_000;

    /** Reintentos de suscripcion mientras el cliente siga conectado */
    private static final int MAX_INTENTOS_SUSCRIPCION = 10;

    /** Pausa entre reintentos de suscripcion */
    private static final long ESPERA_REINTENTO_MS = 3_000;

    private final String url;
    private final ConcurrentMap<String, MqttClient> clientes = new ConcurrentHashMap<>();
    /** Un hilo secuencial por cliente: conserva el orden y libera a Paho */
    private final ConcurrentMap<String, ExecutorService> ejecutores = new ConcurrentHashMap<>();

    public MqttBus(@Value("${mqtt.url:tcp://localhost:1883}") String url) {
        this.url = url;
    }

    @Override
    public void afterPropertiesSet() {
        // Cliente emisor: lo usa el simulador, el API REST y los servicios que
        // publican eventos (recepcion, procesamiento, monitoreo, alertas).
        MqttClient emisor = crearCliente("campus-emisor", true);
        conectarEnBackground(emisor, "emisor", true);
    }

    /**
     * Publica un mensaje en el broker con QoS 1.
     * Si el broker no esta disponible registra el error (Ronda 2: encolar y reintentar).
     */
    public void publicar(String topic, String payload) {
        MqttClient emisor = clientes.get("campus-emisor");
        if (emisor == null || !emisor.isConnected()) {
            log.error("[MQTT] No se pudo publicar en {}: emisor no conectado", topic);
            return;
        }
        try {
            emisor.publish(topic, payload.getBytes(java.nio.charset.StandardCharsets.UTF_8), 1, false);
        } catch (MqttException e) {
            log.error("[MQTT] Error publicando en {}: {}", topic, e.getMessage());
        }
    }

    /**
     * Se suscribe a un topic (QoS 1, sesion persistente).
     *
     * La suscripcion NO se hace de forma bloqueante dentro de connectComplete
     * (ahi corre el hilo de callbacks de Paho): se lanza en un hilo aparte y
     * con reintento, para que el cliente nunca quede mudo esperando un SUBACK.
     * Los mensajes se entregan al manejador desde un ejecutor propio y en
     * orden, de modo que el hilo de Paho queda libre para enviar los PUBACK.
     */
    public void suscribir(String clienteId, String topic, BiConsumer<String, String> manejador) {
        MqttClient cliente = crearCliente(clienteId, false);

        ExecutorService ejecutor = Executors.newSingleThreadExecutor(r -> {
            Thread hilo = new Thread(r, "mqtt-msg-" + clienteId);
            hilo.setDaemon(true);
            return hilo;
        });
        ejecutores.put(clienteId, ejecutor);

        cliente.setCallback(new MqttCallbackExtended() {
            @Override
            public void connectComplete(boolean reconexion, String serverURI) {
                // Nunca suscribirse aqui en modo bloqueante: ver javadoc de la clase
                log.info("[MQTT] {} conectado a {}{}", clienteId, serverURI,
                        reconexion ? " (reconexion)" : "");
                Thread.ofVirtual().name("mqtt-sub-" + clienteId)
                        .start(() -> suscribirConReintento(cliente, clienteId, topic));
            }

            @Override
            public void connectionLost(Throwable causa) {
                log.warn("[MQTT] {} perdio conexion con el broker: {}. Reconexion automatica en curso...",
                        clienteId, causa == null ? "?" : causa.getMessage());
            }

            @Override
            public void messageArrived(String topico, MqttMessage mensaje) {
                // El payload se copia aqui; el trabajo pesado va al ejecutor
                String payload = new String(mensaje.getPayload(),
                        java.nio.charset.StandardCharsets.UTF_8);
                try {
                    ejecutor.execute(() -> {
                        try {
                            manejador.accept(topico, payload);
                        } catch (Exception e) {
                            log.error("[MQTT] Error manejando mensaje de {}: {}", topico, e.getMessage());
                        }
                    });
                } catch (Exception cierre) {
                    log.warn("[MQTT] Mensaje de {} descartado (ejecutor cerrado): {}",
                            topico, cierre.getMessage());
                }
            }

            @Override
            public void deliveryComplete(IMqttDeliveryToken token) {
                // no aplica para suscriptores
            }
        });
        // Suscriptores con sesion no limpia: el broker retiene lo pendiente (E9/E11)
        conectarEnBackground(cliente, clienteId, false);
    }

    /**
     * Suscripcion con reintento, siempre fuera del hilo de callbacks de Paho.
     * Si el cliente se cae, connectComplete vuelve a lanzar este proceso.
     */
    private void suscribirConReintento(MqttClient cliente, String clienteId, String topic) {
        // Un solo proceso de suscripcion por cliente a la vez
        synchronized (cliente) {
            for (int intento = 1; intento <= MAX_INTENTOS_SUSCRIPCION; intento++) {
                if (!cliente.isConnected()) {
                    log.warn("[MQTT] {} sin conexion al suscribirse a '{}'; lo hara al reconectar",
                            clienteId, topic);
                    return;
                }
                try {
                    cliente.subscribe(topic, 1);
                    log.info("[MQTT] {} suscrito a '{}'", clienteId, topic);
                    return;
                } catch (Exception e) {
                    log.warn("[MQTT] {} no pudo suscribirse a '{}' (intento {}/{}): {}. Reintento en {} s",
                            clienteId, topic, intento, MAX_INTENTOS_SUSCRIPCION, e.getMessage(),
                            ESPERA_REINTENTO_MS / 1000);
                    try {
                        Thread.sleep(ESPERA_REINTENTO_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
            log.error("[MQTT] {} agoto los reintentos de suscripcion a '{}'; se reintentara en la proxima reconexion",
                    clienteId, topic);
        }
    }

    private MqttClient crearCliente(String clientId, boolean sesionLimpia) {
        MqttClient cliente;
        try {
            cliente = new MqttClient(url, clientId, new MemoryPersistence());
            // Sin tope, una operacion bloqueante (p.ej. subscribe esperando su
            // SUBACK) quedaria colgada para siempre y hundiria al cliente.
            cliente.setTimeToWait(OPERACION_MS);
        } catch (Exception e) {
            // No debe tumbar el arranque: conectarEnBackground reintenta luego
            throw new IllegalStateException("No se pudo crear el cliente MQTT " + clientId, e);
        }
        clientes.put(clientId, cliente);
        return cliente;
    }

    /**
     * Conexion inicial en hilo propio: reintenta cada 3 s hasta que el broker
     * este disponible, sin bloquear el arranque de Spring Boot.
     *
     * sesionLimpia=true para el emisor (no tiene cola que recuperar) y false
     * para los suscriptores: asi Mosquitto encola los mensajes QoS 1 mientras
     * el servicio este caido y se los entrega al reconectar (E9/E11).
     */
    private void conectarEnBackground(MqttClient cliente, String descripcion, boolean sesionLimpia) {
        MqttConnectOptions opciones = new MqttConnectOptions();
        opciones.setCleanSession(sesionLimpia);
        opciones.setAutomaticReconnect(true);
        // 30 s se quedaba corto: con cualquier latencia el cliente se declaraba
        // muerto y entraba en un bucle de reconexion sin suscripcion activa.
        opciones.setKeepAliveInterval(60);
        opciones.setConnectionTimeout(10);

        Thread.ofVirtual().name("mqtt-" + descripcion).start(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    if (!cliente.isConnected()) {
                        cliente.connect(opciones);
                    }
                    log.info("[MQTT] Conexion establecida con {} ({})", url, descripcion);
                    return;
                } catch (Exception e) {
                    log.warn("[MQTT] Broker no disponible ({}): {}. Reintento en 3 s",
                            descripcion, e.getMessage());
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        });
    }

    @Override
    public void destroy() {
        for (ExecutorService ejecutor : ejecutores.values()) {
            ejecutor.shutdown();
        }
        ejecutores.clear();
        for (MqttClient cliente : clientes.values()) {
            try {
                if (cliente.isConnected()) {
                    cliente.disconnect(1000);
                }
            } catch (Exception e) {
                log.debug("[MQTT] Cierre de conexion de {}: {}", cliente.getClientId(), e.getMessage());
            }
            try {
                cliente.close();
            } catch (Exception e) {
                log.debug("[MQTT] Cierre de cliente {}: {}", cliente.getClientId(), e.getMessage());
            }
        }
        clientes.clear();
    }

    /** Solo para diagnosticos / pruebas. */
    public List<String> clientesConectados() {
        return clientes.entrySet().stream()
                .filter(e -> e.getValue().isConnected())
                .map(java.util.Map.Entry::getKey)
                .toList();
    }
}
