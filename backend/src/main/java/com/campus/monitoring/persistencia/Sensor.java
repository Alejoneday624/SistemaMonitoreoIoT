package com.campus.monitoring.persistencia;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Sensor registrado en el sistema (HDU-05).
 * El estado lo actualiza el servicio de monitoreo (Ronda 2, HDU-04).
 */
@Entity
@Table(name = "sensores")
public class Sensor {

    @Id
    @Column(name = "id", length = 40)
    private String id;

    /** Variable que mide: temperatura | calidad-aire | consumo-electrico | consumo-agua */
    @Column(name = "variable", nullable = false, length = 40)
    private String variable;

    /** Sede: Sur | Federman */
    @Column(name = "sede", nullable = false, length = 40)
    private String sede;

    /** Salón o espacio fisico donde esta instalado */
    @Column(name = "salon", length = 80)
    private String salon;

    /** Frecuencia configurada de envio de mediciones, en segundos */
    @Column(name = "frecuencia_segundos")
    private Integer frecuenciaSegundos;

    /** ACTIVO | INACTIVO (HDU-04) */
    @Column(name = "estado", nullable = false, length = 20)
    private String estado = "ACTIVO";

    /** Fecha y hora de la ultima medicion recibida (base del heartbeat) */
    @Column(name = "ultima_medicion_en")
    private Instant ultimaMedicionEn;

    public Sensor() {
    }

    public Sensor(String id, String variable, String sede, String salon, Integer frecuenciaSegundos) {
        this.id = id;
        this.variable = variable;
        this.sede = sede;
        this.salon = salon;
        this.frecuenciaSegundos = frecuenciaSegundos;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
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

    public Integer getFrecuenciaSegundos() {
        return frecuenciaSegundos;
    }

    public void setFrecuenciaSegundos(Integer frecuenciaSegundos) {
        this.frecuenciaSegundos = frecuenciaSegundos;
    }

    public String getEstado() {
        return estado;
    }

    public void setEstado(String estado) {
        this.estado = estado;
    }

    public Instant getUltimaMedicionEn() {
        return ultimaMedicionEn;
    }

    public void setUltimaMedicionEn(Instant ultimaMedicionEn) {
        this.ultimaMedicionEn = ultimaMedicionEn;
    }
}
