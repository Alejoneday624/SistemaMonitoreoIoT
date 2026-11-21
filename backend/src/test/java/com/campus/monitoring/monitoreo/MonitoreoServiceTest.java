package com.campus.monitoring.monitoreo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.campus.monitoring.config.MqttBus;
import com.campus.monitoring.persistencia.Sensor;
import com.campus.monitoring.persistencia.SensorRepository;

/**
 * Prueba unitaria del heartbeat (T3/HDU-04), sin Docker.
 *
 * - sin latido durante el umbral -> INACTIVO + alerta en interno/alertas;
 * - el latido vuelve -> ACTIVO + evento SENSOR_RECUPERADO;
 * - un sensor apagado por el administrador (T6) -> APAGADO y CERO alertas.
 *
 * La gracia de arranque se desactiva pasando 0 ms: asi el primer tick revisa.
 */
class MonitoreoServiceTest {

    private Sensor sensor(String id, Instant ultima, boolean encendido) {
        Sensor s = new Sensor(id, "temperatura", "Sur", "Salon 101", 5);
        s.setUltimaMedicionEn(ultima);
        s.setEncendido(encendido);
        s.setEstado("ACTIVO");
        // registrado antes que la ultima medicion: la referencia es la medicion
        s.setRegistradoEn(ultima == null ? Instant.now() : ultima.minusSeconds(60));
        s.setEstadoCambiadoEn(ultima == null ? Instant.now() : ultima.minusSeconds(60));
        return s;
    }

    @Test
    void sensorSinLatidoPasaAInactivoYPublicaUnaSolaAlerta() {
        Sensor s = sensor("TMP-SUR-01", Instant.now().minusSeconds(300), true);
        SensorRepository repo = mock(SensorRepository.class);
        when(repo.findAll()).thenReturn(List.of(s));
        doAnswer(inv -> {
            s.setEstado(inv.getArgument(1));
            return 1;
        }).when(repo).actualizarEstado(anyString(), anyString());
        MqttBus mqtt = mock(MqttBus.class);

        MonitoreoService servicio = new MonitoreoService(repo, mqtt, 120, 0);
        servicio.verificarSensores();
        servicio.verificarSensores(); // no debe duplicar la alerta

        verify(repo).actualizarEstado("TMP-SUR-01", MonitoreoService.INACTIVO);
        verify(repo, never()).actualizarEstado("TMP-SUR-01", MonitoreoService.ACTIVO);

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(mqtt, times(1)).publicar(eq(MonitoreoService.TOPIC_ALERTAS), payload.capture());
        assertThat(payload.getValue())
                .contains("SENSOR_INACTIVO")
                .contains("TMP-SUR-01")
                .contains("Sur")
                .contains("Salon 101");
    }

    @Test
    void cuandoVuelveElLatidoSeMarcaActivoYSePublicaLaRecuperacion() {
        Sensor s = sensor("TMP-SUR-02", Instant.now().minusSeconds(300), true);
        SensorRepository repo = mock(SensorRepository.class);
        when(repo.findAll()).thenReturn(List.of(s));
        doAnswer(inv -> {
            s.setEstado(inv.getArgument(1));
            return 1;
        }).when(repo).actualizarEstado(anyString(), anyString());
        MqttBus mqtt = mock(MqttBus.class);
        MonitoreoService servicio = new MonitoreoService(repo, mqtt, 120, 0);

        servicio.verificarSensores(); // alerta

        // Persistencia recibe una medicion nueva y monitoreo la ve ACTIVO
        s.setUltimaMedicionEn(Instant.now());
        s.setEstado(MonitoreoService.INACTIVO);

        servicio.verificarSensores();

        verify(repo).actualizarEstado("TMP-SUR-02", MonitoreoService.ACTIVO);
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(mqtt, times(2)).publicar(eq(MonitoreoService.TOPIC_ALERTAS), payload.capture());
        assertThat(payload.getAllValues().get(1))
                .contains("SENSOR_RECUPERADO")
                .contains("TMP-SUR-02");
    }

    @Test
    void sensorApagadoNoGeneraAlertasDeHeartbeat() {
        Sensor s = sensor("TMP-SUR-03", Instant.now().minusSeconds(300), false);
        SensorRepository repo = mock(SensorRepository.class);
        when(repo.findAll()).thenReturn(List.of(s));
        MqttBus mqtt = mock(MqttBus.class);

        MonitoreoService servicio = new MonitoreoService(repo, mqtt, 120, 0);
        servicio.verificarSensores();

        verify(repo).actualizarEstado("TMP-SUR-03", MonitoreoService.APAGADO);
        verify(mqtt, never()).publicar(anyString(), anyString());
    }
}
