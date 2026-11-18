package com.campus.monitoring.config;

/**
 * Senal de que un componente esta CAIDO a proposito (demo de fallas, E1/E11).
 *
 * No es un error de negocio: es la forma en que un servicio avisa al bus
 * "ahora mismo no puedo procesar, deja el mensaje PENDIENTE y vuelve a
 * intentarlo despues". Por eso MqttBus la trata distinto de cualquier otra
 * excepcion:
 *
 * - NO incrementa los reintentos ni marca FALLIDO (el mensaje no esta
 *   roto, el componente esta fuera de servicio).
 * - Deja la cola de entrada intacta y la reintentara en el proximo tick
 *   (cola.tick-ms), asi que al restaurar el flujo se vacia en orden FIFO
 *   sin perder nada (la tactica E11).
 */
public class ComponenteCaidoException extends RuntimeException {

    private final String componente;

    public ComponenteCaidoException(String componente) {
        super(componente + " caido: mensaje diferido hasta restaurar");
        this.componente = componente;
    }

    public String getComponente() {
        return componente;
    }
}
