package com.campus.monitoring;

import org.junit.jupiter.api.Test;

/**
 * Humo de la aplicacion completa: el contexto arranca con el stack real
 * (Postgres + Mosquitto de StackTest, en Docker) en lugar de necesitar una
 * base de datos local.
 *
 * Hereda StackSpringTest a proposito: asi comparte UN SOLO contexto (y un
 * solo MqttBus) con MqttBusColasTest; si cada clase levantara el suyo, los
 * clientes MQTT se pisarian la sesion en el broker.
 */
class MonitoringApplicationTests extends StackSpringTest {

	@Test
	void contextLoads() {
	}

}
