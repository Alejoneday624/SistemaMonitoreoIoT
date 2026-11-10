package com.campus.monitoring.persistencia;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Registro de errores del sistema (HDU-07): fallas de sensores,
 * mediciones invalidas y errores de comunicacion.
 * Se alimenta desde el topic interno/logs.
 */
@Entity
@Table(name = "errores")
public class ErrorRegistro {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** MEDICION_INVALIDA | SENSOR_INACTIVO | ERROR_COMUNICACION | ... */
    @Column(name = "tipo", nullable = false, length = 40)
    private String tipo;

    @Column(name = "sensor_id", length = 40)
    private String sensorId;

    @Column(name = "fecha", nullable = false)
    private Instant fecha;

    @Column(name = "descripcion", length = 500)
    private String descripcion;

    public ErrorRegistro() {
    }

    public ErrorRegistro(String tipo, String sensorId, Instant fecha, String descripcion) {
        this.tipo = tipo;
        this.sensorId = sensorId;
        this.fecha = fecha;
        this.descripcion = descripcion;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTipo() {
        return tipo;
    }

    public void setTipo(String tipo) {
        this.tipo = tipo;
    }

    public String getSensorId() {
        return sensorId;
    }

    public void setSensorId(String sensorId) {
        this.sensorId = sensorId;
    }

    public Instant getFecha() {
        return fecha;
    }

    public void setFecha(Instant fecha) {
        this.fecha = fecha;
    }

    public String getDescripcion() {
        return descripcion;
    }

    public void setDescripcion(String descripcion) {
        this.descripcion = descripcion;
    }
}
