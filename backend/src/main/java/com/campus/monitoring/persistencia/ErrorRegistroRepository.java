package com.campus.monitoring.persistencia;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ErrorRegistroRepository extends JpaRepository<ErrorRegistro, Long> {

    /**
     * Filtros combinables (Tarea 9 — HDU-07) resueltos EN LA BASE DE DATOS:
     * tipo, sensor y rango de fechas en una sola query, con ORDER BY en la BD.
     *
     * La consulta NO admite parametros nulos. Dos motivos comprobados contra
     * PostgreSQL:
     *  - LOWER(:param) con un nulo liga el parametro como bytea ->
     *    "function lower(bytea) does not exist".
     *  - "param IS NULL" no aporta tipo al parametro -> Hibernate lo envia sin
     *    inferir y Postgres falla con "could not determine data type of parameter".
     *
     * Por eso el controlador envia siempre valores "neutros" en lugar de nulos:
     * cadena vacia = sin filtro de texto, instantes extremos = sin filtro de
     * fechas. Aqui solo se comparan, y las comparaciones si dan tipo a cada
     * parametro (? = '' es text, e.fecha >= ? es timestamp).
     */
    @Query("""
            SELECT e FROM ErrorRegistro e
            WHERE (:tipo = '' OR LOWER(e.tipo) = :tipo)
              AND (:sensorId = '' OR LOWER(e.sensorId) = :sensorId)
              AND e.fecha >= :desde
              AND e.fecha <= :hasta
            ORDER BY e.fecha DESC
            """)
    List<ErrorRegistro> buscarConFiltros(@Param("tipo") String tipo,
                                         @Param("sensorId") String sensorId,
                                         @Param("desde") Instant desde,
                                         @Param("hasta") Instant hasta);
}
