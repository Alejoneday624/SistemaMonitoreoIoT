package com.campus.monitoring.persistencia;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * Medicion valida de un sensor.
 * La restriccion unica (sensor, fecha) es la que materializa la deteccion
 * de duplicados de la HDU-03 a nivel de base de datos.
 */
@Entity
@Table(name = "mediciones",
        uniqueConstraints = @UniqueConstraint(name = "uq_medicion_sensor_fecha",
                columnNames = { "sensor_id", "fecha" }))
public class Medicion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sensor_id", nullable = false, length = 40)
    private String sensorId;

    @Column(name = "variable", nullable = false, length = 40)
    private String variable;

    @Column(name = "sede", nullable = false, length = 40)
    private String sede;

    @Column(name = "salon", length = 80)
    private String salon;

    @Column(name = "valor", nullable = false)
    private Double valor;

    /** Fecha y hora de captura de la medicion (enviada por el sensor) */
    @Column(name = "fecha", nullable = false)
    private Instant fecha;

    /** Instante en que el sistema la recibio (para calcular latencia) */
    @Column(name = "recibida_en")
    private Instant recibidaEn;

    public Medicion() {
    }

    public Medicion(String sensorId, String variable, String sede, String salon,
                    Double valor, Instant fecha, Instant recibidaEn) {
        this.sensorId = sensorId;
        this.variable = variable;
        this.sede = sede;
        this.salon = salon;
        this.valor = valor;
        this.fecha = fecha;
        this.recibidaEn = recibidaEn;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getSensorId() {
        return sensorId;
    }

    public void setSensorId(String sensorId) {
        this.sensorId = sensorId;
    }

    public String getVariable() {
        return variable;
    }

    public void setVariable(String variable) {
        this.variable = variable;
    }

    public String getSede() {
        return sede;
    }

    public void setSede(String sede) {
        this.sede = sede;
    }

    public String getSalon() {
        return salon;
    }

    public void setSalon(String salon) {
        this.salon = salon;
    }

    public Double getValor() {
        return valor;
    }

    public void setValor(Double valor) {
        this.valor = valor;
    }

    public Instant getFecha() {
        return fecha;
    }

    public void setFecha(Instant fecha) {
        this.fecha = fecha;
    }

    public Instant getRecibidaEn() {
        return recibidaEn;
    }

    public void setRecibidaEn(Instant recibidaEn) {
        this.recibidaEn = recibidaEn;
    }
}
