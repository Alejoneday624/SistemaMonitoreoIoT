package com.campus.monitoring.api;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.campus.monitoring.StackSpringTest;
import com.campus.monitoring.persistencia.ErrorRegistro;
import com.campus.monitoring.persistencia.ErrorRegistroRepository;

/**
 * Pruebas de integracion para Tarea 9: Filtros del registro de errores
 * por fecha, tipo y sensor (HDU-07).
 */
class ErroresFiltroTest extends StackSpringTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ErrorRegistroRepository errores;

    private MockMvc mvc;

    @BeforeEach
    void prepararDatos() {
        this.mvc = MockMvcBuilders.webAppContextSetup(context).build();
        errores.deleteAll();

        errores.save(new ErrorRegistro(
                "MEDICION_INVALIDA",
                "TMP-SUR-01",
                Instant.parse("2026-11-10T10:15:00Z"),
                "Valor fuera de rango: 999 C"));

        errores.save(new ErrorRegistro(
                "SENSOR_INACTIVO",
                "TMP-SUR-01",
                Instant.parse("2026-11-12T14:30:00Z"),
                "Sin heartbeat en 120 s"));

        errores.save(new ErrorRegistro(
                "MEDICION_INVALIDA",
                "AQI-FED-01",
                Instant.parse("2026-11-12T18:45:00Z"),
                "JSON malformado"));

        errores.save(new ErrorRegistro(
                "ERROR_COMUNICACION",
                "ELEC-SUR-01",
                Instant.parse("2026-11-13T09:00:00Z"),
                "Timeout de persistencia"));
    }

    @Test
    void filtraPorTipoSensorYFechaIndividualmenteYCombinados() throws Exception {
        // 1. Sin filtros -> devuelve los 4 ordenados por fecha descendente
        mvc.perform(get("/api/errores"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(4)))
                .andExpect(jsonPath("$[0].sensorId").value("ELEC-SUR-01"));

        // 2. Filtro por tipo (?tipo=MEDICION_INVALIDA) -> 2 registros
        mvc.perform(get("/api/errores").param("tipo", "MEDICION_INVALIDA"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));

        // 3. Filtro por sensor (?sensorId=TMP-SUR-01 y alias ?sensor=TMP-SUR-01) -> 2 registros
        mvc.perform(get("/api/errores").param("sensorId", "TMP-SUR-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));

        mvc.perform(get("/api/errores").param("sensor", "AQI-FED-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].tipo").value("MEDICION_INVALIDA"));

        // 4. Filtro por dia exacto (?fecha=2026-11-12) -> los 2 errores de ese dia
        mvc.perform(get("/api/errores").param("fecha", "2026-11-12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));

        // 5. Filtro por rango (?desde=2026-11-12&hasta=2026-11-13) -> 3 registros
        mvc.perform(get("/api/errores")
                        .param("desde", "2026-11-12")
                        .param("hasta", "2026-11-13"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)));

        // 6. Filtros combinados (tipo + sensor + fecha) -> 1 registro exacto
        mvc.perform(get("/api/errores")
                        .param("tipo", "MEDICION_INVALIDA")
                        .param("sensor", "AQI-FED-01")
                        .param("fecha", "2026-11-12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].sensorId").value("AQI-FED-01"));
    }
}

