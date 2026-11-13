package com.campus.monitoring;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base de las pruebas que arrancan la aplicacion COMPLETA.
 *
 * Todo lo que esta aqui (anotacion y metodo de propiedades dinamicas) es
 * UNICO en el arbol de herencia a proposito: asi todos los hijos comparten
 * exactamente la misma configuracion y Spring reutiliza UN SOLO contexto
 * (y por lo tanto UN UNICO MqttBus). Si cada clase levantara su propio
 * contexto habria dos clientes MQTT con el mismo clientId peleandose por la
 * sesion en el broker.
 *
 * Ajustes de determinismo:
 * - sensor.frecuencia-ms alto: el simulador no publica durante los tests.
 * - cola.tick-ms alto: las colas solo se drenan cuando el test lo pide
 *   (mqtt.drenarColas()) o cuando el broker reconecta, nunca por sorpresa.
 */
@SpringBootTest(properties = {
        "mqtt.url=tcp://localhost:" + StackTest.PUERTO_MQTT,
        "sensor.frecuencia-ms=3600000",
        "cola.tick-ms=600000",
        "cola.reintentos-max=3",
        "spring.jpa.show-sql=false"
})
public abstract class StackSpringTest extends StackTest {

    @DynamicPropertySource
    static void datosDelStack(DynamicPropertyRegistry registro) {
        propiedades(registro);
    }
}
