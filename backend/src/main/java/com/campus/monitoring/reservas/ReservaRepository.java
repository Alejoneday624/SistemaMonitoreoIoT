package com.campus.monitoring.reservas;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReservaRepository extends JpaRepository<Reserva, Long> {

    List<Reserva> findAllByOrderByInicioDesc();

    List<Reserva> findByUsuarioIdOrderByInicioDesc(String usuarioId);

    List<Reserva> findByEstadoOrderByInicioAsc(String estado);

    /**
     * Cuenta cuantas reservas ACTIVAS se superponen con el intervalo [inicio, fin).
     * Dos intervalos [A, B) y [C, D) se solapan si y solo si A < D y B > C.
     */
    @Query("""
            SELECT COUNT(r) FROM Reserva r
            WHERE r.estado = 'ACTIVA'
              AND r.inicio < :fin
              AND r.fin > :inicio
            """)
    long contarActivasSolapadas(@Param("inicio") Instant inicio, @Param("fin") Instant fin);

    /**
     * Verifica si el mismo usuario ya tiene una reserva ACTIVA que se solape con [inicio, fin).
     */
    @Query("""
            SELECT COUNT(r) > 0 FROM Reserva r
            WHERE r.estado = 'ACTIVA'
              AND LOWER(r.usuarioId) = LOWER(:usuarioId)
              AND r.inicio < :fin
              AND r.fin > :inicio
            """)
    boolean existeSolapamientoUsuario(@Param("usuarioId") String usuarioId,
                                      @Param("inicio") Instant inicio,
                                      @Param("fin") Instant fin);

    /**
     * Verifica si un puesto especifico (1..50) ya esta reservado en un horario que se solape.
     */
    @Query("""
            SELECT COUNT(r) > 0 FROM Reserva r
            WHERE r.estado = 'ACTIVA'
              AND r.puesto = :puesto
              AND r.inicio < :fin
              AND r.fin > :inicio
            """)
    boolean existeSolapamientoPuesto(@Param("puesto") Integer puesto,
                                     @Param("inicio") Instant inicio,
                                     @Param("fin") Instant fin);
}

