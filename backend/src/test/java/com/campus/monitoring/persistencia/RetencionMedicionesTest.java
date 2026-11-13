package com.campus.monitoring.persistencia;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import com.campus.monitoring.StackTest;

/**
 * Retencion de mediciones (disco de8 GB en la instancia): solo se purga lo
 * que supera el umbral configurado; lo reciente jamas se toca.
 */
@DataJpaTest
@Import(RetencionMediciones.class)
@TestPropertySource(properties = {
        "mediciones.dias-retencion=30"
})
class RetencionMedicionesTest extends StackTest {

    @DynamicPropertySource
    static void datosDelStack(DynamicPropertyRegistry registro) {
        propiedades(registro);
    }

    @Autowired
    private RetencionMediciones retencion;

    @Autowired
    private MedicionRepository repo;

    @BeforeEach
    void limpiar() {
        // La base es compartida por toda la suite y otros tests (por ejemplo
        // MqttBusColasTest) dejan mediciones commiteadas: sin este arranque
        // limpio los conteos exactos fallarian con filas ajenas.
        repo.deleteAll();
    }

    @Test
    void purgaSoloLoQueSuperaElUmbral() {
        Instant ahora = Instant.now();
        repo.save(new Medicion("TMP-01", "temperatura", "Sur", "Salon 101", 21.5,
                ahora.minus(Duration.ofDays(45)), ahora));   // vieja: purgada
        repo.save(new Medicion("TMP-01", "temperatura", "Sur", "Salon 101", 22.0,
                ahora.minus(Duration.ofDays(1)), ahora));    // reciente: intacta
        repo.save(new Medicion("TMP-01", "temperatura", "Sur", "Salon 101", 22.5,
                ahora.minus(Duration.ofDays(35)), ahora));   // vieja2: purgada
        assertThat(repo.count()).isEqualTo(3);

        retencion.purgar();

        assertThat(repo.count()).isEqualTo(1);
        assertThat(repo.findAll()).extracting(Medicion::getSensorId).containsExactly("TMP-01");
        assertThat(repo.findAll()).extracting(Medicion::getValor).containsExactly(22.0);
    }

    @Test
    void sinViejasNoBorraNada() {
        repo.save(new Medicion("TMP-02", "humedad", "Norte", "Salon 201", 55.0,
                Instant.now(), Instant.now()));

        retencion.purgar();

        assertThat(repo.count()).isEqualTo(1);
    }
}
