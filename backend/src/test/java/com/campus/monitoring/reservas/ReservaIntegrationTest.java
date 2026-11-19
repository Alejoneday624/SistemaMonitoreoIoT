package com.campus.monitoring.reservas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.campus.monitoring.StackSpringTest;

/**
 * Pruebas de integracion para Tarea 8: Reservas de biblioteca (50 cupos, sin superposiciones — HDU-09).
 *
 * Verifica:
 * 1. Creacion de 50 reservas simultaneas y rechazo de la 51ava reserva (criterio del README).
 * 2. Rechazo de reservas con superposicion de horario para el mismo usuario o mismo puesto.
 * 3. Liberacion de cupo al cancelar una reserva o al reservar en horario no superpuesto.
 */
class ReservaIntegrationTest extends StackSpringTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ReservaRepository repo;

    private MockMvc mvc;

    @BeforeEach
    void preparar() {
        this.mvc = MockMvcBuilders.webAppContextSetup(context).build();
        repo.deleteAll();
    }

    @Test
    void permite50ReservasEnMismoHorarioYRechazaLa51ava() throws Exception {
        String inicio = "2026-11-15T14:00:00Z";
        String fin = "2026-11-15T16:00:00Z";

        Long ultimaReservaId = null;

        // Crear las primeras 50 reservas en la misma franja horaria
        for (int i = 1; i <= 50; i++) {
            String payload = """
                    {
                      "usuarioId": "estudiante-%d",
                      "puesto": %d,
                      "sede": "Sur",
                      "inicio": "%s",
                      "fin": "%s"
                    }
                    """.formatted(i, i, inicio, fin);

            String resp = mvc.perform(post("/api/reservas")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payload))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.estado").value("ACTIVA"))
                    .andReturn().getResponse().getContentAsString();

            if (i == 50) {
                ultimaReservaId = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(resp).get("id").asLong();
            }
        }

        // Verificar que la disponibilidad ahora reporta 50 ocupados y 0 disponibles
        mvc.perform(get("/api/reservas/disponibilidad")
                        .param("inicio", inicio)
                        .param("fin", fin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.capacidadMaxima").value(50))
                .andExpect(jsonPath("$.cuposOcupados").value(50))
                .andExpect(jsonPath("$.cuposDisponibles").value(0));

        // Intentar crear la 51ava reserva en el mismo horario (o solapado parcialmente) -> 409 Conflict
        String payload51 = """
                {
                  "usuarioId": "estudiante-51",
                  "sede": "Sur",
                  "inicio": "2026-11-15T15:00:00Z",
                  "fin": "2026-11-15T17:00:00Z"
                }
                """;
        mvc.perform(post("/api/reservas")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload51))
                .andExpect(status().isConflict());

        assertThat(repo.count()).isEqualTo(50);

        // Si el estudiante-51 reserva en un horario SIN superposicion (16:00 a 18:00), SI se permite
        String payloadSinSolapar = """
                {
                  "usuarioId": "estudiante-51",
                  "sede": "Sur",
                  "inicio": "2026-11-15T16:00:00Z",
                  "fin": "2026-11-15T18:00:00Z"
                }
                """;
        mvc.perform(post("/api/reservas")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payloadSinSolapar))
                .andExpect(status().isCreated());

        // Y si cancelamos la reserva #50 del bloque 14:00-16:00, se libera 1 cupo en ese bloque
        mvc.perform(delete("/api/reservas/" + ultimaReservaId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("CANCELADA"));

        mvc.perform(get("/api/reservas/disponibilidad")
                        .param("inicio", inicio)
                        .param("fin", fin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cuposOcupados").value(49))
                .andExpect(jsonPath("$.cuposDisponibles").value(1));
    }

    @Test
    void rechazaSuperposicionDeHorarioParaMismoUsuarioOMismoPuesto() throws Exception {
        // Primera reserva de ana.gomez en puesto 10 de 08:00 a 10:00
        String r1 = """
                {
                  "usuarioId": "ana.gomez",
                  "puesto": 10,
                  "inicio": "2026-11-16T08:00:00Z",
                  "fin": "2026-11-16T10:00:00Z"
                }
                """;
        mvc.perform(post("/api/reservas")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(r1))
                .andExpect(status().isCreated());

        // Misma usuaria intenta reservar otro puesto en horario solapado (09:00 a 11:00) -> 409 Conflict
        String solapeUsuario = """
                {
                  "usuarioId": "ana.gomez",
                  "puesto": 11,
                  "inicio": "2026-11-16T09:00:00Z",
                  "fin": "2026-11-16T11:00:00Z"
                }
                """;
        mvc.perform(post("/api/reservas")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(solapeUsuario))
                .andExpect(status().isConflict());

        // Otro usuario intenta reservar el MISMO puesto 10 en horario solapado -> 409 Conflict
        String solapePuesto = """
                {
                  "usuarioId": "carlos.ruiz",
                  "puesto": 10,
                  "inicio": "2026-11-16T09:30:00Z",
                  "fin": "2026-11-16T10:30:00Z"
                }
                """;
        mvc.perform(post("/api/reservas")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(solapePuesto))
                .andExpect(status().isConflict());
    }
}

