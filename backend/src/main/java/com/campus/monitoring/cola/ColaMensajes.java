package com.campus.monitoring.cola;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * Mensaje encolado de un servicio (tacticas E9/E11 del documento).
 *
 * La misma tabla sostiene las dos colas que pide la arquitectura:
 *
 * - cola = "salida": OUTBOX del emisor. Todo lo que se publica en el broker
 *   se guarda AQUI primero; si el broker esta caido el mensaje espera y se
 *   publica al reconectar (nada se descarta). Si el backend se cae, la cola
 *   sigue viva en PostgreSQL: al volver se vacia.
 *
 * - cola = "entrada:{clienteId}/{topic}": INBOX de cada suscriptor. El
 *   mensaje se registra ANTES de que Paho envie el PUBACK; si el servicio se
 *   muere justo despues, al arrancar reprocesa lo que quede PENDIENTE (el
 *   broker por su parte reentrega lo no confirmado).
 *
 * Solo quedan filas pendientes o fallidas: al procesarse/confirmarse se
 * eliminan, asi que la tabla nunca crece en el camino feliz.
 */
@Entity
@Table(name = "cola_mensajes",
        indexes = @Index(name = "idx_cola_estado_id", columnList = "cola, estado, id"))
public class ColaMensajes {

    /** Estado: esperando ser procesado/publicado */
    public static final String PENDIENTE = "PENDIENTE";

    /** Estado: agoto los reintentos; se conserva para diagnostico (HDU-07) */
    public static final String FALLIDO = "FALLIDO";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Clave logica de la cola (salida | entrada:clienteId/topic) */
    @Column(nullable = false, length = 200)
    private String cola;

    /** Topic MQTT de origen (para reentregarlo tal cual al manejador) */
    @Column(nullable = false, length = 255)
    private String topic;

    @Column(nullable = false, columnDefinition = "text")
    private String payload;

    /** Cuando entro a la cola (para orden FIFO y purgas) */
    @Column(nullable = false)
    private Instant creadoEn;

    /** Veces que fallo el procesamiento (control de mensajes toxicos) */
    @Column(nullable = false)
    private int intentos;

    @Column(nullable = false, length = 16)
    private String estado;

    protected ColaMensajes() {
        // JPA
    }

    public ColaMensajes(String cola, String topic, String payload, Instant creadoEn) {
        this.cola = cola;
        this.topic = topic;
        this.payload = payload;
        this.creadoEn = creadoEn;
        this.intentos = 0;
        this.estado = PENDIENTE;
    }

    public Long getId() {
        return id;
    }

    public String getCola() {
        return cola;
    }

    public String getTopic() {
        return topic;
    }

    public String getPayload() {
        return payload;
    }

    public Instant getCreadoEn() {
        return creadoEn;
    }

    public int getIntentos() {
        return intentos;
    }

    public void setIntentos(int intentos) {
        this.intentos = intentos;
    }

    public String getEstado() {
        return estado;
    }

    public void setEstado(String estado) {
        this.estado = estado;
    }

    @Override
    public String toString() {
        return "ColaMensajes{id=" + id + ", cola='" + cola + "', topic='" + topic
                + "', intentos=" + intentos + ", estado='" + estado + "'}";
    }
}
