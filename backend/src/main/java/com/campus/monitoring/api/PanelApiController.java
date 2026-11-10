package com.campus.monitoring.api;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.campus.monitoring.persistencia.ErrorRegistro;
import com.campus.monitoring.persistencia.ErrorRegistroRepository;
import com.campus.monitoring.persistencia.Medicion;
import com.campus.monitoring.persistencia.MedicionRepository;
import com.campus.monitoring.persistencia.Sensor;
import com.campus.monitoring.persistencia.SensorRepository;

/**
 * API REST del tablero (Tabla 32: ofrece endpoints para el tablero).
 * El frontend las consume via proxy inverso de nginx (/api/ -> backend).
 */
@RestController
@RequestMapping("/api")
public class PanelApiController {

    /** Sensores sin datos recientes se marcan desactualizados (HDU-01) */
    private static final Duration VIGENCIA = Duration.ofSeconds(90);

    private final SensorRepository sensores;
    private final MedicionRepository mediciones;
    private final ErrorRegistroRepository errores;

    public PanelApiController(SensorRepository sensores, MedicionRepository mediciones,
                              ErrorRegistroRepository errores) {
        this.sensores = sensores;
        this.mediciones = mediciones;
        this.errores = errores;
    }

    /** HDU-01: estado de todos los sensores + vigencia de sus datos */
    @GetMapping("/sensores")
    public List<Map<String, Object>> sensores() {
        Instant ahora = Instant.now();
        List<Map<String, Object>> salida = new ArrayList<>();
        for (Sensor s : sensores.findAll()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.getId());
            m.put("variable", s.getVariable());
            m.put("sede", s.getSede());
            m.put("salon", s.getSalon());
            m.put("estado", s.getEstado());
            m.put("ultimaMedicionEn", s.getUltimaMedicionEn());
            boolean desactualizado = s.getUltimaMedicionEn() == null
                    || Duration.between(s.getUltimaMedicionEn(), ahora).compareTo(VIGENCIA) > 0;
            m.put("desactualizado", desactualizado);
            m.put("antiguedadSegundos", s.getUltimaMedicionEn() == null ? null
                    : Duration.between(s.getUltimaMedicionEn(), ahora).getSeconds());
            salida.add(m);
        }
        return salida;
    }

    /** Ultimas mediciones (opcional por variable) para las graficas del tablero */
    @GetMapping("/mediciones")
    public List<Medicion> mediciones(
            @RequestParam(required = false) String variable,
            @RequestParam(defaultValue = "120") int limite) {
        int top = Math.min(Math.max(limite, 1), 500);
        if (variable == null || variable.isBlank()) {
            return mediciones.findAllByOrderByFechaDesc(PageRequest.of(0, top));
        }
        return mediciones.findByVariableOrderByFechaDesc(variable, PageRequest.of(0, top));
    }

    /** Registro de errores con filtro por tipo (HDU-07) */
    @GetMapping("/errores")
    public List<ErrorRegistro> errores(@RequestParam(required = false) String tipo) {
        if (tipo == null || tipo.isBlank()) {
            return errores.findAllByOrderByFechaDesc();
        }
        return errores.findByTipoOrderByFechaDesc(tipo);
    }

    /** Resumen operativo: cobertura del monitoreo en este momento */
    @GetMapping("/estado")
    public Map<String, Object> estado() {
        List<Sensor> todos = sensores.findAll();
        Instant ahora = Instant.now();
        long activos = todos.stream()
                .filter(s -> s.getUltimaMedicionEn() != null
                        && Duration.between(s.getUltimaMedicionEn(), ahora).compareTo(VIGENCIA) <= 0)
                .count();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sensoresTotales", todos.size());
        m.put("sensoresActivos", activos);
        m.put("sensoresInactivos", todos.size() - activos);
        m.put("medicionesTotales", mediciones.count());
        m.put("erroresRegistrados", errores.count());
        m.put("medicionesUltimaHora", mediciones.countByFechaAfter(ahora.minus(Duration.ofHours(1))));
        m.put("servidorHora", ahora);
        return m;
    }
}
