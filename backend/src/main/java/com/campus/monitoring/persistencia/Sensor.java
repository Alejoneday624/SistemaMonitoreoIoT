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

    /** Valor base con el que el simulador genera las lecturas de este sensor */
    @Column(name = "valor_base")
    private Double valorBase;

    /** Frecuencia configurada de envio de mediciones, en segundos */
    @Column(name = "frecuencia_segundos")
    private Integer frecuenciaSegundos;

    /** ACTIVO | INACTIVO | APAGADO (HDU-04 / HDU-05) */
    @Column(name = "estado", nullable = false, length = 20)
    private String estado = "ACTIVO";

    /** Fecha y hora de la ultima medicion recibida (base del heartbeat) */
    @Column(name = "ultima_medicion_en")
    private Instant ultimaMedicionEn;

    /**
     * Interruptor del sensor (T6 / HDU-05): true = publica, false = apagado
     * por el administrador. Se persiste para sobrevivir reinicios y se cambia
     * por una orden MQTT en control/simulador/orden.
     */
    @Column(name = "encendido")
    private Boolean encendido = true;

    /** Alta del sensor: da una gracia antes de exigirle el primer heartbeat */
    @Column(name = "registrado_en")
    private Instant registradoEn;

    /** Ultimo cambio de encendido/apagado: reinicia la gracia del heartbeat */
    @Column(name = "estado_cambiado_en")
    private Instant estadoCambiadoEn;

    public Sensor() {
    }

    public Sensor(String id, String variable, String sede, String salon, Integer frecuenciaSegundos) {
        this.id = id;
        this.variable = variable;
        this.sede = sede;
        this.salon = salon;
        this.frecuenciaSegundos = frecuenciaSegundos;
        this.registradoEn = Instant.now();
        this.estadoCambiadoEn = this.registradoEn;
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

    public Double getValorBase() {
        return valorBase;
    }

    public void setValorBase(Double valorBase) {
        this.valorBase = valorBase;
    }

    /** Asignacion en cadena, para sembrar el catalogo por defecto */
    public Sensor conValorBase(Double valorBase) {
        this.valorBase = valorBase;
        return this;
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

    public Boolean getEncendido() {
        return encendido;
    }

    public void setEncendido(Boolean encendido) {
        this.encendido = encendido;
    }

    /** null en filas antiguas = encendido (compatibilidad con datos previos) */
    public boolean estaEncendido() {
        return !Boolean.FALSE.equals(encendido);
    }

    public Instant getRegistradoEn() {
        return registradoEn;
    }

    public void setRegistradoEn(Instant registradoEn) {
        this.registradoEn = registradoEn;
    }

    public Instant getEstadoCambiadoEn() {
        return estadoCambiadoEn;
    }

    public void setEstadoCambiadoEn(Instant estadoCambiadoEn) {
        this.estadoCambiadoEn = estadoCambiadoEn;
    }
}
