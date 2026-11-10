package com.campus.monitoring.persistencia;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MedicionRepository extends JpaRepository<Medicion, Long> {

    /** Ultimas mediciones para el tablero (HDU-01) */
    List<Medicion> findAllByOrderByFechaDesc(Pageable pageable);

    /** Ultimas mediciones de una variable concreta (para las graficas) */
    List<Medicion> findByVariableOrderByFechaDesc(String variable, Pageable pageable);

    /** Ultima medicion de una variable en una sede */
    List<Medicion> findTop1ByVariableAndSedeOrderByFechaDesc(String variable, String sede);

    /** Para el heartbeat: ultima fecha de cada sensor (HDU-04) */
    List<Medicion> findTop1BySensorIdOrderByFechaDesc(String sensorId);

    long countByFechaAfter(Instant desde);
}
