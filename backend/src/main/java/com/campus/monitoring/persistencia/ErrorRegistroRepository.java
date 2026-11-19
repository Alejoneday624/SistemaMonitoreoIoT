package com.campus.monitoring.persistencia;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ErrorRegistroRepository extends JpaRepository<ErrorRegistro, Long> {

    /**
     * Filtros combinables (Tarea 9 — HDU-07) resueltos EN LA BASE DE DATOS:
     * cualquier subconjunto de tipo / sensor / rango de fechas puede venir nulo,
     * y solo se agregan las condiciones pedidas. Ordena por fecha descendente.
     *
     * Nota: los textos llegan ya en minusculas (el controlador normaliza) y se
     * comparan con LOWER(columna) = :param. No se usa LOWER(:param) porque
     * Hibernate ligaria un nulo sin tipo y PostgreSQL fallaria con
     * "function lower(bytea) does not exist".
     */
    @Query("""
            SELECT e FROM ErrorRegistro e
            WHERE (:tipo IS NULL OR LOWER(e.tipo) = :tipo)
              AND (:sensorId IS NULL OR LOWER(e.sensorId) = :sensorId)
              AND (:desde IS NULL OR e.fecha >= :desde)
              AND (:hasta IS NULL OR e.fecha <= :hasta)
            ORDER BY e.fecha DESC
            """)
    List<ErrorRegistro> buscarConFiltros(@Param("tipo") String tipo,
                                         @Param("sensorId") String sensorId,
                                         @Param("desde") Instant desde,
                                         @Param("hasta") Instant hasta);
}
