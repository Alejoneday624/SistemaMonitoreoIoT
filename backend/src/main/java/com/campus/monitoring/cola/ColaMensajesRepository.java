package com.campus.monitoring.cola;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

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

    /**
     * Resumen por cola y estado (para el panel: ver crecer la cola cuando un
     * componente interno cae). Devuelve [cola, estado, total].
     */
    @Query("select c.cola, c.estado, count(c) from ColaMensajes c "
            + "group by c.cola, c.estado order by c.cola, c.estado")
    List<Object[]> resumenPorCola();

    /** Purga de los FALLIDO con mas de X tiempo (housekeeping) */
    void deleteByEstadoAndCreadoEnBefore(String estado, Instant limite);
}
