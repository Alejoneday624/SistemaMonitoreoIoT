package com.campus.monitoring.reservas;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Servicio de reservas de biblioteca (Tarea 8 — HDU-09).
 *
 * Garantiza:
 * 1. Capacidad maxima de 50 cupos simultaneos en cualquier franja horaria.
 *    La reserva numero 51 en un horario solapado se rechaza con HTTP 409 Conflict.
 * 2. Sin superposiciones de horario para un mismo usuario ni para un mismo puesto.
 */
@Service
public class ReservaService {

    public static final int CAPACIDAD_MAXIMA = 50;

    private final ReservaRepository reservas;

    public ReservaService(ReservaRepository reservas) {
        this.reservas = reservas;
    }

    public List<Reserva> listar(String usuarioId, boolean soloActivas) {
        if (usuarioId != null && !usuarioId.isBlank()) {
            return reservas.findByUsuarioIdOrderByInicioDesc(usuarioId.trim());
        }
        if (soloActivas) {
            return reservas.findByEstadoOrderByInicioAsc(Reserva.ESTADO_ACTIVA);
        }
        return reservas.findAllByOrderByInicioDesc();
    }

    @Transactional
    public synchronized Reserva crearReserva(String usuarioId, Integer puesto, String sede,
                                             Instant inicio, Instant fin) {
        if (usuarioId == null || usuarioId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El campo usuarioId (o usuario) es obligatorio");
        }
        if (inicio == null || fin == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Las fechas de inicio y fin son obligatorias");
        }
        if (!fin.isAfter(inicio)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "La fecha de fin debe ser posterior a la fecha de inicio");
        }
        if (puesto != null && (puesto < 1 || puesto > CAPACIDAD_MAXIMA)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El numero de puesto debe estar entre 1 y " + CAPACIDAD_MAXIMA);
        }

        String usuarioLimpio = usuarioId.trim();

        // 1. Validar que el mismo usuario no tenga otra reserva activa superpuesta
        if (reservas.existeSolapamientoUsuario(usuarioLimpio, inicio, fin)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "El usuario " + usuarioLimpio + " ya tiene una reserva activa que se superpone en ese horario");
        }

        // 2. Validar que el puesto (si se especifico) no este ocupado en ese horario
        if (puesto != null && reservas.existeSolapamientoPuesto(puesto, inicio, fin)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "El puesto " + puesto + " ya esta reservado en el horario solicitado");
        }

        // 3. Validar aforo maximo de la biblioteca (50 cupos simultaneos)
        long ocupados = reservas.contarActivasSolapadas(inicio, fin);
        if (ocupados >= CAPACIDAD_MAXIMA) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Capacidad maxima de la biblioteca alcanzada (" + CAPACIDAD_MAXIMA
                            + " cupos ocupados en ese horario). No se permiten mas reservas.");
        }

        Reserva nueva = new Reserva(usuarioLimpio, puesto, sede, inicio, fin);
        return reservas.save(nueva);
    }

    @Transactional
    public Reserva cancelarReserva(Long id) {
        Reserva r = reservas.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Reserva no encontrada: " + id));
        r.setEstado(Reserva.ESTADO_CANCELADA);
        return reservas.save(r);
    }

    public Map<String, Object> consultarDisponibilidad(Instant inicio, Instant fin) {
        if (inicio == null || fin == null || !fin.isAfter(inicio)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Debe proporcionar un rango valido con inicio < fin");
        }
        long ocupados = reservas.contarActivasSolapadas(inicio, fin);
        long disponibles = Math.max(0, CAPACIDAD_MAXIMA - ocupados);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("capacidadMaxima", CAPACIDAD_MAXIMA);
        m.put("cuposOcupados", ocupados);
        m.put("cuposDisponibles", disponibles);
        m.put("inicio", inicio);
        m.put("fin", fin);
        return m;
    }

    /**
     * Parsea fechas en formato ISO-8601 Instant (2026-11-13T10:00:00Z),
     * LocalDateTime (2026-11-13T10:00:00) o LocalDate (2026-11-13).
     */
    public static Instant parsearFechaHora(String texto, String nombreCampo) {
        if (texto == null || texto.isBlank()) {
            return null;
        }
        String s = texto.trim();
        try {
            return Instant.parse(s);
        } catch (DateTimeParseException ignored) {
        }
        try {
            return LocalDateTime.parse(s).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
        }
        try {
            return LocalDate.parse(s).atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Formato de fecha invalido en '" + nombreCampo + "': " + texto + " (use ISO-8601)");
        }
    }
}

