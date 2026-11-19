package com.campus.monitoring.persistencia;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ErrorRegistroRepository extends JpaRepository<ErrorRegistro, Long> {

    List<ErrorRegistro> findAllByOrderByFechaDesc();

    List<ErrorRegistro> findByTipoOrderByFechaDesc(String tipo);

    List<ErrorRegistro> findBySensorIdOrderByFechaDesc(String sensorId);

    List<ErrorRegistro> findByFechaBetweenOrderByFechaDesc(Instant desde, Instant hasta);
}

