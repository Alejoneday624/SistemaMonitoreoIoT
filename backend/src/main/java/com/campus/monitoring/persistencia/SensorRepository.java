package com.campus.monitoring.persistencia;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface SensorRepository extends JpaRepository<Sensor, String> {

    List<Sensor> findBySedeOrderByVariableAscIdAsc(String sede);

    /** Sensores apagados por el administrador (T6): no publican y no alertan */
    List<Sensor> findByEncendidoFalse();

    /**
     * Cambia SOLO el estado del sensor. Se usa en el heartbeat para no pisar
     * con una entidad vieja la ultima medicion que guardo Persistencia en
     * paralelo (evita perder el latido mas reciente).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update Sensor s set s.estado = :estado where s.id = :id")
    int actualizarEstado(@Param("id") String id, @Param("estado") String estado);

    @Query("select max(m.fecha) from Medicion m where m.sensorId = :sensorId")
    java.time.Instant ultimaFechaDe(@Param("sensorId") String sensorId);
}
