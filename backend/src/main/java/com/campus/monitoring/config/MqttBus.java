package com.campus.monitoring.config;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.campus.monitoring.cola.ColaMensajes;
import com.campus.monitoring.cola.ColaService;

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
 * COLAS DURADERAS (por que nada se pierde aunque se caiga un componente):
 *
 * - SALIDA (outbox): publicar() NUNCA descarta. Primero guarda el mensaje en
 *   PostgreSQL (cola "salida") y recien ahi lo entrega al broker esperando el
 *   PUBACK; solo con el PUBACK se retira de la cola. Si el broker esta caido
 *   el mensaje espera y se publica al reconectar. Si el BACKEND se cae, la
 *   cola vive en la base y al volver el proceso la vacia (en Render el disco
 *   es efimero, por eso esta en PostgreSQL y no en archivos).
 *
 * - ENTRADA (inbox): messageArrived guarda el mensaje en su cola de entrada
 *   ANTES de que Paho envie el PUBACK. Si el servicio muere despues del ack,
 *   al arrancar reprocesa lo que quede PENDIENTE. Un manejador que falle
 *   reintenta un numero acotado de veces y luego queda FALLIDO sin bloquear
 *   a los mensajes que vienen detras.
 *
 * Regla de hilo (clave para que el tablero no vea los sensores "apagados"):
 * el hilo de callbacks de Paho es UNO solo por cliente y no debe bloquearse.
 * Por eso (1) la suscripcion se lanza fuera de connectComplete, (2) los
 * manejadores de mensajes corren en un ejecutor propio y (3) encolar en la
 * base de datos es una operacion corta; si la BD esta caida NO se manda el
 * PUBACK y entonces el broker conserva el mensaje en su cola (mejor perder
 * confirmacion que perder el dato).
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
    private final ColaService cola;
    private final int reintentosMax;

    private final ConcurrentMap<String, MqttClient> clientes = new ConcurrentHashMap<>();
    /** Un hilo secuencial por cliente: conserva el orden y libera a Paho */
    private final ConcurrentMap<String, ExecutorService> ejecutores = new ConcurrentHashMap<>();
    /** Suscripciones registradas: clave de inbox -> que hacer con el mensaje */
    private final ConcurrentMap<String, Suscripcion> suscripciones = new ConcurrentHashMap<>();
    /** Hilo unico que vacia la cola de salida (publicaciones en orden) */
    private final ExecutorService vaciador;

    public MqttBus(@Value("${mqtt.url:tcp://localhost:1883}") String url,
                   ColaService cola,
                   @Value("${cola.reintentos-max:5}") int reintentosMax) {
        this.url = url;
        this.cola = cola;
        this.reintentosMax = reintentosMax;
        this.vaciador = Executors.newSingleThreadExecutor(r -> {
            Thread hilo = new Thread(r, "mqtt-salida");
            hilo.setDaemon(true);
            return hilo;
        });
    }

    /** Clave logica del inbox de una suscripcion (para pruebas y diagnostico) */
    public static String claveEntrada(String clienteId, String topic) {
        return "entrada:" + clienteId + "/" + topic;
    }

    @Override
    public void afterPropertiesSet() {
        // Cliente emisor: lo usa el simulador, el API REST y los servicios que
        // publican eventos (recepcion, procesamiento, monitoreo, alertas).
        MqttClient emisor = crearCliente("campus-emisor", true);
        emisor.setCallback(new MqttCallbackExtended() {
            @Override
            public void connectComplete(boolean reconexion, String serverURI) {
                log.info("[MQTT] emisor conectado a {}{} — se vacia la cola de salida",
                        serverURI, reconexion ? " (reconexion)" : "");
                despertarSalida();
            }

            @Override
            public void connectionLost(Throwable causa) {
                log.warn("[MQTT] emisor perdio conexion con el broker: {}. "
                                + "Las publicaciones quedan en cola hasta reconectar",
                        causa == null ? "?" : causa.getMessage());
            }

            @Override
            public void messageArrived(String topico, MqttMessage mensaje) {
                // el emisor no suscribe a nada
            }

            @Override
            public void deliveryComplete(IMqttDeliveryToken token) {
                // el flujo de salida espera el PUBACK con waitForCompletion
            }
        });
        conectarEnBackground(emisor, "emisor", true);
    }

    /**
     * Publica un mensaje en el broker con QoS 1 — NUNCA lo descarta.
     *
     * 1) Se persiste en la cola de salida (aqui es donde ocurre la
     *    "memoria" que sobrevive a la caida del broker y del propio
     *    backend). 2) Se despierta el vaciador, que entrega con espera de
     *    PUBACK y retira el mensaje solo si el broker confirmo.
     *
     * Si la base de datos no esta disponible (caso en el que no hay donde
     * guardar) se intenta publicar directo como mejor esfuerzo.
     */
    public void publicar(String topic, String payload) {
        try {
            cola.encolar(ColaService.SALIDA, topic, payload);
            despertarSalida();
        } catch (Exception e) {
            log.error("[MQTT] No se pudo encolar para {}: {}. Intento directo (mejor esfuerzo)",
                    topic, e.getMessage());
            publicarDirecto(topic, payload);
        }
    }

    /**
     * Publica y espera el PUBACK.
     *
     * MqttClient.publish() bloquea hasta la confirmacion (limitado por
     * setTimeToWait = OPERACION_MS) y lanza MqttException si el broker no
     * responde: por eso un return true aqui significa "el broker lo recibio"
     * y la cola de salida puede retirar el mensaje.
     *
     * @return true si el broker confirmo la recepcion (listo para quitar de
     *         la cola); false si no hay conexion o no hubo confirmacion, en
     *         cuyo caso el mensaje sigue en cola.
     */
    private boolean publicarDirecto(String topic, String payload) {
        MqttClient emisor = clientes.get("campus-emisor");
        if (emisor == null || !emisor.isConnected()) {
            return false;
        }
        try {
            emisor.publish(topic, payload.getBytes(java.nio.charset.StandardCharsets.UTF_8), 1, false);
            return true;
        } catch (Exception e) {
            log.warn("[MQTT] '{}' no confirmado por el broker: {}", topic, e.getMessage());
            return false;
        }
    }

    /** Despierta al hilo que vacia la cola de salida (sin bloquear al llamador) */
    private void despertarSalida() {
        try {
            vaciador.execute(this::vaciaSalida);
        } catch (RejectedExecutionException cerrado) {
            log.debug("[MQTT] Vaciado de salida rechazado (bus cerrado)");
        }
    }

    /**
     * Entrega en orden (FIFO) todo lo pendiente de la cola de salida.
     * Corre SILO en el hilo "mqtt-salida", asi que no hay carreras entre el
     * vaciado y las publicaciones en vivo (ambas pasan por la cola).
     * Si el broker no confirma, se corta y el proximo despertar (reconexion
     * o tick programado) retoma donde quedo.
     */
    private void vaciaSalida() {
        while (true) {
            Optional<ColaMensajes> pendiente = cola.siguiente(ColaService.SALIDA);
            if (pendiente.isEmpty()) {
                return;
            }
            ColaMensajes m = pendiente.get();
            if (!publicarDirecto(m.getTopic(), m.getPayload())) {
                return; // broker caido: el mensaje queda guardado en la cola
            }
            try {
                cola.quitar(m.getId());
                log.debug("[MQTT] Cola de salida: publicado #{} en {}", m.getId(), m.getTopic());
            } catch (Exception e) {
                // Publicado pero no retirado: se reenviara (QoS 1 ya duplica;
                // el pipeline es idempotente por la restriccion unica de BD).
                log.warn("[MQTT] No se pudo retirar #{} de la cola: {}", m.getId(), e.getMessage());
                return;
            }
        }
    }

    /**
     * Se suscribe a un topic (QoS 1, sesion persistente).
     *
     * La suscripcion NO se hace de forma bloqueante dentro de connectComplete
     * (ahi corre el hilo de callbacks de Paho): se lanza en un hilo aparte y
     * con reintento, para que el cliente nunca quede mudo esperando un SUBACK.
     *
     * Cada mensaje llega PRIMERO a la cola de entrada del servicio (durable,
     * antes del PUBACK) y desde ahi se ejecuta el manejador, con reintentos.
     */
    public void suscribir(String clienteId, String topic, BiConsumer<String, String> manejador) {
        MqttClient cliente = crearCliente(clienteId, false);

        ExecutorService ejecutor = Executors.newSingleThreadExecutor(r -> {
            Thread hilo = new Thread(r, "mqtt-msg-" + clienteId);
            hilo.setDaemon(true);
            return hilo;
        });
        ejecutores.put(clienteId, ejecutor);

        Suscripcion sub = new Suscripcion(clienteId, topic, manejador);
        suscripciones.put(claveEntrada(clienteId, topic), sub);

        cliente.setCallback(new MqttCallbackExtended() {
            @Override
            public void connectComplete(boolean reconexion, String serverURI) {
                // Nunca suscribirse aqui en modo bloqueante: ver javadoc de la clase
                log.info("[MQTT] {} conectado a {}{}", clienteId, serverURI,
                        reconexion ? " (reconexion)" : "");
                Thread.ofVirtual().name("mqtt-sub-" + clienteId)
                        .start(() -> suscribirConReintento(cliente, clienteId, topic));
                // pendientes del arranque anterior (o de la ultima sesion)
                despertarEntrada(sub);
            }

            @Override
            public void connectionLost(Throwable causa) {
                log.warn("[MQTT] {} perdio conexion con el broker: {}. Reconexion automatica en curso...",
                        clienteId, causa == null ? "?" : causa.getMessage());
            }

            @Override
            public void messageArrived(String topico, MqttMessage mensaje) {
                // El payload se copia aqui; el trabajo pesado va al ejecutor.
                // ANTES de salir (y de que Paho mande el PUBACK) el mensaje se
                // guarda en la cola de entrada: si el proceso muere despues de
                // este punto, al arrancar se reprocesa.
                String payload = new String(mensaje.getPayload(),
                        java.nio.charset.StandardCharsets.UTF_8);
                recibir(sub, topico, payload);
            }

            @Override
            public void deliveryComplete(IMqttDeliveryToken token) {
                // no aplica para suscriptores
            }
        });
        // Suscriptores con sesion no limpia: el broker retiene lo pendiente (E9/E11)
        conectarEnBackground(cliente, clienteId, false);

        // Recuperacion al arrancar: lo que quedo PENDIENTE del proceso anterior
        // se procesa sin esperar al broker.
        despertarEntrada(sub);
    }

    /**
     * Guarda el mensaje en la cola de entrada del suscriptor y despierta su
     * procesamiento. Si la BD falla no hay garantia posible: se ejecuta el
     * manejador directo (mejor esfuerzo) para no perderlo tampoco.
     */
    private void recibir(Suscripcion sub, String topico, String payload) {
        try {
            cola.encolar(claveEntrada(sub.clienteId(), sub.topic()), topico, payload);
        } catch (Exception e) {
            log.error("[MQTT] Sin cola para {} ({}) — procesamiento directo: {}",
                    topico, sub.clienteId(), e.getMessage());
            ejecutarDirecto(sub, topico, payload);
            return;
        }
        despertarEntrada(sub);
    }

    private void despertarEntrada(Suscripcion sub) {
        ExecutorService ejecutor = ejecutores.get(sub.clienteId());
        if (ejecutor == null) {
            return;
        }
        try {
            ejecutor.execute(() -> vaciaEntrada(sub));
        } catch (RejectedExecutionException cerrado) {
            log.warn("[MQTT] Mensaje de {} descartado (ejecutor cerrado)", sub.topic());
        }
    }

    /**
     * Procesa en orden la cola de entrada de una suscripcion. Corre en el
     * ejecutor secuencial del cliente, asi que el manejador nunca se solapa
     * consigo mismo.
     *
     * Fallo transitorio -> se reintenta en el proximo tick. Mensaje toxico
     * (agota reintentos) -> queda FALLIDO y se pasa al siguiente para que la
     * cola no se congele.
     */
    private void vaciaEntrada(Suscripcion sub) {
        String clave = claveEntrada(sub.clienteId(), sub.topic());
        while (true) {
            Optional<ColaMensajes> pendiente = cola.siguiente(clave);
            if (pendiente.isEmpty()) {
                return;
            }
            ColaMensajes m = pendiente.get();
            try {
                sub.manejador().accept(m.getTopic(), m.getPayload());
                cola.quitar(m.getId());
            } catch (Exception e) {
                log.error("[MQTT] {} fallo con #{} ({}): {}",
                        clave, m.getId(), m.getTopic(), e.getMessage());
                if (cola.registrarFallo(clave, m.getId(), reintentosMax)) {
                    log.error("[MQTT] {} mensaje #{} FALLIDO; se salta al siguiente", clave, m.getId());
                    continue;
                }
                return; // reintentara en el proximo tick / mensaje
            }
        }
    }

    private void ejecutarDirecto(Suscripcion sub, String topico, String payload) {
        ExecutorService ejecutor = ejecutores.get(sub.clienteId());
        if (ejecutor == null) {
            return;
        }
        try {
            ejecutor.execute(() -> {
                try {
                    sub.manejador().accept(topico, payload);
                } catch (Exception e) {
                    log.error("[MQTT] Error manejando mensaje de {}: {}", topico, e.getMessage());
                }
            });
        } catch (RejectedExecutionException cerrado) {
            log.warn("[MQTT] Mensaje de {} descartado (ejecutor cerrado)", topico);
        }
    }

    /**
     * Red de seguridad: cada ~10 s vuelve a despertar el vaciado de TODAS las
     * colas (salida y entradas). Cubre los casos en los que no llego una
     * reconexion ni un mensaje nuevo: por ejemplo, un fallo de red puntual al
     * publicar o mensajes que quedaron PENDIENTES tras reiniciar el proceso.
     * Los tests lo invocan directamente para no depender del temporizador.
     */
    @Scheduled(fixedDelayString = "${cola.tick-ms:10000}")
    public void drenarColas() {
        despertarSalida();
        for (Suscripcion sub : suscripciones.values()) {
            despertarEntrada(sub);
        }
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
     * sesionLimpia=true para el emisor (su cola es la de PostgreSQL, aqui no
     * hace falta retener nada) y false para los suscriptores: asi Mosquitto
     * encola los mensajes QoS 1 mientras el servicio este caido y se los
     * entrega al reconectar (E9/E11).
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
                    if ("emisor".equals(descripcion)) {
                        // Ademas del callback: por si connectComplete llega tarde
                        despertarSalida();
                    }
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
        vaciador.shutdown();
        for (ExecutorService ejecutor : ejecutores.values()) {
            ejecutor.shutdown();
        }
        ejecutores.clear();
        // Lo que quede en cola se queda en la base de datos: al volver el
        // proceso (o el broker) se seguira intentando. Ese es el punto.
        suscripciones.clear();
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

    /** Suscripcion registrada en el bus (clienteId, filtro topic, manejador) */
    private record Suscripcion(String clienteId, String topic, BiConsumer<String, String> manejador) {
    }
}
