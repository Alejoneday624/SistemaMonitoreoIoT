package com.campus.monitoring.reservas;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * Reserva de puesto en la biblioteca del campus (Tarea 8 — HDU-09).
 *
 * Capacidad maxima: 50 cupos simultaneos sin superposiciones de horario.
 */
@Entity
@Table(
    name = "reservas",
    indexes = {
        @Index(name = "idx_reservas_estado_horario", columnList = "estado, inicio, fin"),
        @Index(name = "idx_reservas_usuario", columnList = "usuario_id")
    }
)
public class Reserva {

    public static final String ESTADO_ACTIVA = "ACTIVA";
    public static final String ESTADO_CANCELADA = "CANCELADA";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "usuario_id", nullable = false, length = 80)
    private String usuarioId;

    /** Numero de puesto opcional (1..50) */
    @Column(name = "puesto")
    private Integer puesto;

    @Column(name = "sede", nullable = false, length = 40)
    private String sede = "Sur";

    @Column(name = "inicio", nullable = false)
    private Instant inicio;

    @Column(name = "fin", nullable = false)
    private Instant fin;

    @Column(name = "estado", nullable = false, length = 20)
    private String estado = ESTADO_ACTIVA;

    @Column(name = "creada_en", nullable = false)
    private Instant creadaEn;

    public Reserva() {
    }

    public Reserva(String usuarioId, Integer puesto, String sede, Instant inicio, Instant fin) {
        this.usuarioId = usuarioId;
        this.puesto = puesto;
        this.sede = (sede == null || sede.isBlank()) ? "Sur" : sede;
        this.inicio = inicio;
        this.fin = fin;
        this.estado = ESTADO_ACTIVA;
        this.creadaEn = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsuarioId() {
        return usuarioId;
    }

    public void setUsuarioId(String usuarioId) {
        this.usuarioId = usuarioId;
    }

    public Integer getPuesto() {
        return puesto;
    }

    public void setPuesto(Integer puesto) {
        this.puesto = puesto;
    }

    public String getSede() {
        return sede;
    }

    public void setSede(String sede) {
        this.sede = sede;
    }

    public Instant getInicio() {
        return inicio;
    }

    public void setInicio(Instant inicio) {
        this.inicio = inicio;
    }

    public Instant getFin() {
        return fin;
    }

    public void setFin(Instant fin) {
        this.fin = fin;
    }

    public String getEstado() {
        return estado;
    }

    public void setEstado(String estado) {
        this.estado = estado;
    }

    public Instant getCreadaEn() {
        return creadaEn;
    }

    public void setCreadaEn(Instant creadaEn) {
        this.creadaEn = creadaEn;
    }
}

