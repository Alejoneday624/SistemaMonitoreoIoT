package com.campus.monitoring.api;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.campus.monitoring.monitoreo.MonitoreoService;
import com.campus.monitoring.persistencia.ErrorRegistro;
import com.campus.monitoring.persistencia.ErrorRegistroRepository;

/**
 * API del monitoreo de sensores (T3 — HDU-04).
 *
 * GET /api/monitoreo          -> resumen del heartbeat: umbral, ultima
 *                                comprobacion y estado de cada sensor.
 * GET /api/monitoreo/alertas  -> historial de alertas del heartbeat
 *                                (SENSOR_INACTIVO / SENSOR_RECUPERADO),
 *                                archivadas por Persistencia desde
 *                                interno/alertas.
 */
@RestController
@RequestMapping("/api/monitoreo")
public class MonitoreoController {

    private final MonitoreoService monitoreo;
    private final ErrorRegistroRepository errores;

    public MonitoreoController(MonitoreoService monitoreo, ErrorRegistroRepository errores) {
        this.monitoreo = monitoreo;
        this.errores = errores;
    }

    @GetMapping
    public Map<String, Object> estado() {
        return monitoreo.resumen();
    }

    @GetMapping("/alertas")
    public List<ErrorRegistro> alertas(
            @RequestParam(defaultValue = "SENSOR_INACTIVO") String tipo,
            @RequestParam(defaultValue = "50") int limite) {
        int tope = Math.min(Math.max(limite, 1), 200);
        String filtro = tipo == null || tipo.isBlank() ? "SENSOR_INACTIVO" : tipo.trim();
        return errores.findTop200ByTipoIgnoreCaseOrderByFechaDesc(filtro)
                .stream()
                .limit(tope)
                .toList();
    }
}
