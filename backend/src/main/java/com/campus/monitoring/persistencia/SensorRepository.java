package com.campus.monitoring.persistencia;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SensorRepository extends JpaRepository<Sensor, String> {

    List<Sensor> findBySedeOrderByVariableAscIdAsc(String sede);

    @Query("select max(m.fecha) from Medicion m where m.sensorId = :sensorId")
    java.time.Instant ultimaFechaDe(@Param("sensorId") String sensorId);
}
