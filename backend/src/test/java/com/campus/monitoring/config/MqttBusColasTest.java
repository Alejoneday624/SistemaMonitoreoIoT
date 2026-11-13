package com.campus.monitoring.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;

import com.campus.monitoring.StackSpringTest;
import com.campus.monitoring.cola.ColaMensajes;
import com.campus.monitoring.cola.ColaMensajesRepository;
import com.campus.monitoring.cola.ColaService;
import com.campus.monitoring.persistencia.Medicion;
import com.campus.monitoring.persistencia.MedicionRepository;

/**
 * Pruebas de TOLERANCIA A FALLAS del bus MQTT, con Postgres y Mosquitto
 * reales (contenedor de Docker que se PARA y ARRANCA para simular la averia).
 *
 * Escenarios cubiertos:
 *  1. Se cae el BROKER -> cada publicacion queda en la cola de salida
 *     (outbox en PostgreSQL) y al volver el broker todo fluye al pipeline:
 *     ninguna medicion se pierde. Es la garantia "si el broker se cae, la
 *     informacion se mantiene en cada servicio en cola y se recupera".
 *  2. Se cae el SERVICIO tras el PUBACK -> el mensaje huérfano de la cola de
 *     entrada se reprocesa al arrancar (drenar), sin depender del broker.
 *  3. Mensaje toxico -> agota reintentos, queda FALLIDO y NO bloquea a los
 *     mensajes que vienen detras.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MqttBusColasTest extends StackSpringTest {

    /** Sede-marca: solo para filtrar las mediciones de este test */
    private static final String MARCA_SALIDA = "TestOutbox";
    private static final String MARCA_ENTRADA = "TestInbox";

    @Autowired
    private MqttBus mqtt;

    @Autowired
    private ColaService cola;

    @Autowired
    private ColaMensajesRepository repo;

    @Autowired
    private MedicionRepository mediciones;

    // ------------------------------------------------------------------
    // 1) Broker caido -> outbox -> recuperacion completa
    // ------------------------------------------------------------------
    @Test
    @Order(1)
    void conElBrokerCaidoNadaSePierdeYAlVolverTodoFluye() throws Exception {
        esperarPipelineVivo(); // el stack funciona ANTES de provocar la averia

        // --- AVERIA: se cae el broker ---
        pararBroker();

        // 5 mediciones mientras no hay broker: se guardan en la cola de salida
        Instant base = Instant.now();
        for (int i = 0; i < 5; i++) {
            mqtt.publicar("campus/sur/temperatura/IT-OUTBOX",
                    medicionJson(MARCA_SALIDA, "IT-OUTBOX", base.plusSeconds(i)));
        }

        // Nada llega a la base de datos... pero TODO quedo encolado
        assertThat(medicionesDe(MARCA_SALIDA)).isEmpty();
        assertThat(cola.pendientes(ColaService.SALIDA)).isEqualTo(5);

        // --- RECUPERACION: vuelve el broker ---
        arrancarBroker();

        await().atMost(90, TimeUnit.SECONDS).pollInterval(java.time.Duration.ofSeconds(1))
                .until(() -> cola.pendientes(ColaService.SALIDA) == 0);

        // ...y las 5 llegaron por el pipeline completo (recepcion ->
        // procesamiento -> persistencia). Cero perdidas.
        assertThat(medicionesDe(MARCA_SALIDA)).hasSize(5);
    }

    // ------------------------------------------------------------------
    // 2) Servicio caido tras el PUBACK -> inbox reprocesado al arrancar
    // ------------------------------------------------------------------
    @Test
    @Order(2)
    void mensajeHuerfanoDeLaColaDeEntradaSeProcesaAlDrenar() {
        // Simula el peor caso: Paho ya envio el PUBACK y el proceso murio
        // justo despues. En la vida real lo deja el crash; aqui se inserta
        // directo para no matar el contenedor a proposito.
        String clave = MqttBus.claveEntrada("campus-recepcion", "campus/#");
        long huerfano = cola.encolar(clave, "campus/sur/temperatura/IT-ENTRADA",
                medicionJson(MARCA_ENTRADA, "IT-ENTRADA", Instant.now()));
        assertThat(cola.pendientes(clave)).isEqualTo(1);

        // Al "arrancar" (drenar colas, igual que hace el tick y el
        // connectComplete) el mensaje se reprocesa hasta el final.
        await().atMost(60, TimeUnit.SECONDS).pollInterval(java.time.Duration.ofSeconds(1))
                .until(() -> {
                    mqtt.drenarColas();
                    return cola.pendientes(clave) == 0;
                });

        assertThat(repo.findById(huerfano)).isEmpty(); // ya se retiro
        assertThat(medicionesDe(MARCA_ENTRADA)).hasSize(1);
    }

    // ------------------------------------------------------------------
    // 3) Mensaje toxico -> FALLIDO sin congelar la cola
    // ------------------------------------------------------------------
    @Test
    @Order(3)
    void unMensajeToxicoNoBloqueaALosSiguientes() {
        AtomicInteger procesados = new AtomicInteger();
        mqtt.suscribir("test-veneno", "interno/prueba-veneno", (topic, payload) -> {
            if (payload.contains("VENENO")) {
                throw new IllegalStateException("fallo deliberado del test");
            }
            procesados.incrementAndGet();
        });
        String clave = MqttBus.claveEntrada("test-veneno", "interno/prueba-veneno");

        long toxico = cola.encolar(clave, "interno/prueba-veneno", "VENENO");
        long sano = cola.encolar(clave, "interno/prueba-veneno", "TODO-BIEN");

        // reintentos-max = 3 (ver StackSpringTest): al agotarlos queda FALLIDO
        await().atMost(60, TimeUnit.SECONDS).pollInterval(java.time.Duration.ofSeconds(1))
                .until(() -> {
                    mqtt.drenarColas();
                    return repo.findById(toxico)
                            .map(m -> ColaMensajes.FALLIDO.equals(m.getEstado()))
                            .orElse(false);
                });

        assertThat(repo.findById(toxico).orElseThrow().getIntentos()).isGreaterThanOrEqualTo(3);

        // y el mensaje sano que venia DETRAS si se proceso: la cola no se congelo
        await().atMost(30, TimeUnit.SECONDS).pollInterval(java.time.Duration.ofSeconds(1))
                .until(() -> cola.pendientes(clave) == 0);
        assertThat(repo.findById(sano)).isEmpty();
        assertThat(procesados.get()).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // Ayudantes
    // ------------------------------------------------------------------

    /** Publica hasta que una medicion completa el pipeline (prueba de vida) */
    private void esperarPipelineVivo() {
        await().atMost(90, TimeUnit.SECONDS).pollInterval(java.time.Duration.ofSeconds(2))
                .until(() -> {
                    mqtt.publicar("campus/sur/temperatura/IT-VIVO",
                            medicionJson("TestVivo", "IT-VIVO-" + System.nanoTime(), Instant.now()));
                    return medicionesDe("TestVivo").size() >= 1;
                });
        // que la cola de salida quede limpia antes de provocar la averia
        await().atMost(30, TimeUnit.SECONDS)
                .until(() -> cola.pendientes(ColaService.SALIDA) == 0);
    }

    private List<Medicion> medicionesDe(String sede) {
        return mediciones.findAll().stream().filter(m -> sede.equals(m.getSede())).toList();
    }

    private String medicionJson(String sede, String sensorId, Instant fecha) {
        return "{\"sensorId\":\"%s\",\"variable\":\"temperatura\",\"sede\":\"%s\","
                .formatted(sensorId, sede)
                + "\"salon\":\"Salon 101\",\"valor\":21.5,\"fecha\":\"%s\"}".formatted(fecha);
    }

    /** Corte de red real del broker: docker stop manda SIGTERM (mosquitto
     *  persiste su estado y sale limpio) y docker start lo devuelve con el
     *  mismo puerto fijo. Se usa el CLI para no depender de APIs internas. */
    private static void pararBroker() throws Exception {
        docker("stop", "-t", "10", MOSQUITTO.getContainerId());
    }

    private static void arrancarBroker() throws Exception {
        docker("start", MOSQUITTO.getContainerId());
    }

    private static void docker(String... args) throws Exception {
        java.util.List<String> orden = new java.util.ArrayList<>();
        orden.add("docker");
        orden.addAll(List.of(args));
        Process p = new ProcessBuilder(orden).redirectErrorStream(true).start();
        // esperar a que termine (con tope: el test no debe quedarse colgado)
        if (!p.waitFor(60, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IllegalStateException("docker " + String.join(" ", args) + " no termino");
        }
        if (p.exitValue() != 0) {
            String salida = new String(p.getInputStream().readAllBytes());
            throw new IllegalStateException("docker " + String.join(" ", args)
                    + " fallo (" + p.exitValue() + "): " + salida.trim());
        }
    }
}
