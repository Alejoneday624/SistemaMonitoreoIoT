package com.campus.monitoring.cola;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import com.campus.monitoring.StackTest;

/**
 * Pruebas de la cola duradera (Postgres real, sin broker): orden, tope y
 * reintentos. Es la pieza que sostiene tanto el outbox (broker caido) como
 * el inbox (servicio caido).
 */
@DataJpaTest
@Import(ColaService.class)
@TestPropertySource(properties = {
        "cola.max-pendientes=3",
        "cola.reintentos-max=2",
        "cola.fallidos-dias=7"
})
class ColaServiceTest extends StackTest {

    @DynamicPropertySource
    static void datosDelStack(DynamicPropertyRegistry registro) {
        propiedades(registro);
    }

    @Autowired
    private ColaService cola;

    @Autowired
    private ColaMensajesRepository repo;

    @Test
    void losMensajesSeTomanEnOrdenFifo() {
        long primero = cola.encolar(ColaService.SALIDA, "interno/validadas", "{\"n\":1}");
        long segundo = cola.encolar(ColaService.SALIDA, "interno/validadas", "{\"n\":2}");
        long tercero = cola.encolar(ColaService.SALIDA, "interno/validadas", "{\"n\":3}");

        assertThat(cola.pendientes(ColaService.SALIDA)).isEqualTo(3);

        // se retira uno a uno, siempre el mas viejo primero
        assertThat(cola.siguiente(ColaService.SALIDA).orElseThrow().getId()).isEqualTo(primero);
        cola.quitar(primero);
        assertThat(cola.siguiente(ColaService.SALIDA).orElseThrow().getId()).isEqualTo(segundo);
        cola.quitar(segundo);
        assertThat(cola.siguiente(ColaService.SALIDA).orElseThrow().getId()).isEqualTo(tercero);
        cola.quitar(tercero);
        assertThat(cola.siguiente(ColaService.SALIDA)).isEmpty();
    }

    @Test
    void alLlegarAlTopeSeDescartanLosMasViejosYNoLosNuevos() {
        // tope = 3 (ver @TestPropertySource)
        long viejo = cola.encolar(ColaService.SALIDA, "t", "1");
        cola.encolar(ColaService.SALIDA, "t", "2");
        cola.encolar(ColaService.SALIDA, "t", "3");
        cola.encolar(ColaService.SALIDA, "t", "4"); // desborda -> expulsa al mas viejo

        assertThat(cola.pendientes(ColaService.SALIDA)).isEqualTo(3);
        assertThat(repo.findById(viejo)).isEmpty();          // el viejo salio
        assertThat(cola.siguiente(ColaService.SALIDA).orElseThrow().getPayload())
                .isEqualTo("2");                            // quedan los recientes
    }

    @Test
    void unFalloNoBloqueaYTrasLosReintentosQuedaFallido() {
        String clave = "entrada:cli/interno/x";
        long toxico = cola.encolar(clave, "interno/x", "VENENO");
        long sano = cola.encolar(clave, "interno/x", "TODO-BIEN");

        // reintentos-max = 2: primer fallo sigue en pie...
        assertThat(cola.registrarFallo(clave, toxico, 2)).isFalse();
        // ...segundo fallo -> FALLIDO
        assertThat(cola.registrarFallo(clave, toxico, 2)).isTrue();

        ColaMensajes fallido = repo.findById(toxico).orElseThrow();
        assertThat(fallido.getEstado()).isEqualTo(ColaMensajes.FALLIDO);
        assertThat(fallido.getIntentos()).isEqualTo(2);

        // el fallido ya no es PENDIENTE: la cola avanza al siguiente mensaje
        assertThat(cola.siguiente(clave).orElseThrow().getId()).isEqualTo(sano);
        assertThat(cola.pendientes(clave)).isEqualTo(1);
    }
}
