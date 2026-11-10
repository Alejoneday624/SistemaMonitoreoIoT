package com.campus.monitoring.persistencia;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ErrorRegistroRepository extends JpaRepository<ErrorRegistro, Long> {

    List<ErrorRegistro> findAllByOrderByFechaDesc();

    List<ErrorRegistro> findByTipoOrderByFechaDesc(String tipo);
}
