package com.campus.monitoring.cola;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Acceso a la tabla de colas (salida e entrada de cada servicio).
 * Todas las consultas son por (cola, estado) para conservar el orden FIFO
 * por id dentro de cada cola.
 */
public interface ColaMensajesRepository extends JpaRepository<ColaMensajes, Long> {

    /** Siguiente mensaje PENDIENTE de una cola (el mas viejo) */
    Optional<ColaMensajes> findFirstByColaAndEstadoOrderByIdAsc(String cola, String estado);

    /** Cuantos pendientes hay (tope de la cola y diagnosticos) */
    long countByColaAndEstado(String cola, String estado);

    /** Purga de los FALLIDO con mas de X tiempo (housekeeping) */
    void deleteByEstadoAndCreadoEnBefore(String estado, Instant limite);
}
