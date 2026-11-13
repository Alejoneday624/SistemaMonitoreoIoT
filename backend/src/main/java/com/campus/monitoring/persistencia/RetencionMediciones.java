package com.campus.monitoring.persistencia;

import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Retencion de mediciones: purga periodicamente los registros con mas de
 * N dias para que la tabla no crezca sin limite (el disco de la instancia
 * es de8 GB y en free tier no conviene llenarlo).
 *
 * Mismo patron que ColaService.purgarFallidos: tarea horaria, umbral
 * configurable (MEDICIONES_DIAS_RETENCION) y fallo tragado para que un
 * problema de housekeeping no detenga la aplicacion.
 */
@Component
public class RetencionMediciones {

    private static final Logger log = LoggerFactory.getLogger(RetencionMediciones.class);

    private final MedicionRepository mediciones;
    private final int diasRetencion;

    public RetencionMediciones(MedicionRepository mediciones,
                               @Value("${mediciones.dias-retencion:30}") int diasRetencion) {
        this.mediciones = mediciones;
        this.diasRetencion = diasRetencion;
    }

    /** Cada hora: borra lo que supere el umbral; lo recien llegado jamas. */
    @Scheduled(fixedDelayString = "${mediciones.purgar-ms:3600000}")
    @Transactional
    public void purgar() {
        try {
            Instant corte = Instant.now().minus(Duration.ofDays(diasRetencion));
            long viejas = mediciones.countByFechaBefore(corte);
            if (viejas > 0) {
                mediciones.deleteByFechaBefore(corte);
                log.info("[RETENCION] Purgadas {} mediciones con mas de {} dias",
                        viejas, diasRetencion);
            }
        } catch (Exception e) {
            log.debug("[RETENCION] Purga no ejecutada: {}", e.getMessage());
        }
    }
}
