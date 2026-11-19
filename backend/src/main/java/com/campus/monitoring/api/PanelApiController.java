package com.campus.monitoring.api;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.campus.monitoring.persistencia.ErrorRegistro;
import com.campus.monitoring.persistencia.ErrorRegistroRepository;
import com.campus.monitoring.persistencia.Medicion;
import com.campus.monitoring.persistencia.MedicionRepository;
import com.campus.monitoring.persistencia.Sensor;
import com.campus.monitoring.persistencia.SensorRepository;
import com.campus.monitoring.sensor.SensorSimulator;

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
    private final SensorSimulator simulador;

    public PanelApiController(SensorRepository sensores, MedicionRepository mediciones,
                              ErrorRegistroRepository errores, SensorSimulator simulador) {
        this.sensores = sensores;
        this.mediciones = mediciones;
        this.errores = errores;
        this.simulador = simulador;
    }

    /** HDU-01: estado de todos los sensores + vigencia de sus datos */
    @GetMapping("/sensores")
    public List<Map<String, Object>> sensores() {
        Instant ahora = Instant.now();
        java.util.Set<String> apagados = simulador.estadoApagados().keySet();
        List<Map<String, Object>> salida = new ArrayList<>();
        for (Sensor s : sensores.findAll()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.getId());
            m.put("variable", s.getVariable());
            m.put("sede", s.getSede());
            m.put("salon", s.getSalon());
            m.put("valorBase", s.getValorBase());
            m.put("estado", s.getEstado());
            m.put("apagado", apagados.contains(s.getId()));
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

    /**
     * Registro de errores con filtros combinables por tipo, sensor y fecha (Tarea 9 — HDU-07).
     *
     * Parametros soportados (todos opcionales y combinables):
     * - tipo: p.ej. MEDICION_INVALIDA, SENSOR_INACTIVO, ERROR_COMUNICACION
     * - sensorId (o alias sensor): p.ej. TMP-SUR-01
     * - fecha: dia en formato YYYY-MM-DD (filtra todo ese dia en UTC)
     * - desde / hasta: rango de fechas en formato YYYY-MM-DD o ISO-8601 Instant
     */
    @GetMapping("/errores")
    public List<ErrorRegistro> errores(
            @RequestParam(required = false) String tipo,
            @RequestParam(required = false) String sensorId,
            @RequestParam(required = false) String sensor,
            @RequestParam(required = false) String fecha,
            @RequestParam(required = false) String desde,
            @RequestParam(required = false) String hasta) {

        String filtroTipo = limpiar(tipo);
        String filtroSensor = limpiar(sensorId) != null ? limpiar(sensorId) : limpiar(sensor);

        Instant limiteDesde = null;
        Instant limiteHasta = null;

        if (limpiar(fecha) != null) {
            Instant[] rangoDia = parsearDiaOInstante(fecha.trim());
            limiteDesde = rangoDia[0];
            limiteHasta = rangoDia[1];
        }
        if (limpiar(desde) != null) {
            limiteDesde = parsearLimite(desde.trim(), false);
        }
        if (limpiar(hasta) != null) {
            limiteHasta = parsearLimite(hasta.trim(), true);
        }

        // Los filtros se aplican EN LA BASE DE DATOS (WHERE + ORDER BY), no en
        // memoria: cualquier combinacion de tipo/sensor/fechas viaja en una sola
        // query y la BD ordena por fecha descendente.
        return errores.buscarConFiltros(filtroTipo, filtroSensor, limiteDesde, limiteHasta);
    }

    private static String limpiar(String valor) {
        return (valor == null || valor.isBlank()) ? null : valor.trim();
    }

    private static Instant[] parsearDiaOInstante(String texto) {
        try {
            LocalDate dia = LocalDate.parse(texto);
            Instant inicioDia = dia.atStartOfDay(ZoneOffset.UTC).toInstant();
            Instant finDia = dia.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().minusNanos(1);
            return new Instant[]{inicioDia, finDia};
        } catch (DateTimeParseException ignored) {
        }
        Instant instante = parsearLimite(texto, false);
        return new Instant[]{instante, instante};
    }

    private static Instant parsearLimite(String texto, boolean finDeDiaSiSoloFecha) {
        try {
            return Instant.parse(texto);
        } catch (DateTimeParseException ignored) {
        }
        try {
            return LocalDateTime.parse(texto).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
        }
        try {
            LocalDate dia = LocalDate.parse(texto);
            if (finDeDiaSiSoloFecha) {
                return dia.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().minusNanos(1);
            }
            return dia.atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Formato de fecha invalido: " + texto + " (use YYYY-MM-DD o ISO-8601)");
        }
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
