package com.campus.monitoring;

import java.util.List;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

/**
 * Stack real para los tests: PostgreSQL + Mosquitto en Docker (los mismos
 * que usa docker compose), levantados UNA sola vez por JVM (Ryuk los cierra
 * al terminar) y compartidos por todas las clases de prueba.
 *
 * El broker usa PUERTO FIJO en el host porque MqttBus se configura con esa
 * URL al arrancar el contexto y necesitamos parar/arrancar el contenedor en
 * los tests de caida sin cambiar la URL (asi se simula la averia real).
 *
 * Requiere Docker encendido:  ./mvnw test
 */
public abstract class StackTest {

    /** Puerto fijo del broker de pruebas (ver mqtt.url en StackSpringTest) */
    public static final int PUERTO_MQTT = 18883;

    public static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("campusiot");

    public static final GenericContainer<?> MOSQUITTO = crearMosquitto();

    static {
        POSTGRES.start();
        MOSQUITTO.start();
    }

    private static GenericContainer<?> crearMosquitto() {
        GenericContainer<?> contenedor = new GenericContainer<>("eclipse-mosquitto:2")
                .withExposedPorts(1883)
                .withCopyFileToContainer(
                        MountableFile.forClasspathResource("mosquitto-test.conf"),
                        "/mosquitto/config/mosquitto.conf")
                .waitingFor(Wait.forListeningPort());
        // Puerto del host fijo: la URL de mqtt.url sigue valiendo aunque el
        // contenedor pare y arranque (asi se provoca la caida en los tests).
        contenedor.setPortBindings(List.of(PUERTO_MQTT + ":1883"));
        return contenedor;
    }

    /**
     * Propiedades dinamicas del stack (los puertos efimeros de Postgres se
     * conocen recien al arrancar el contenedor).
     */
    public static void propiedades(DynamicPropertyRegistry registro) {
        registro.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registro.add("spring.datasource.username", POSTGRES::getUsername);
        registro.add("spring.datasource.password", POSTGRES::getPassword);
        registro.add("spring.jpa.show-sql", () -> "false");
    }
}
