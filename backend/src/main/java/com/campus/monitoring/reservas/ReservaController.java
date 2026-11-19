package com.campus.monitoring.reservas;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * API REST para reservas de la biblioteca (Tarea 8 — HDU-09).
 *
 * - POST /api/reservas: crea una reserva validando los 50 cupos y no superposicion.
 * - GET /api/reservas: lista las reservas registradas.
 * - GET /api/reservas/disponibilidad: consulta cupos ocupados y disponibles (sobre 50).
 * - DELETE /api/reservas/{id}: cancela una reserva liberando su cupo.
 */
@RestController
@RequestMapping("/api/reservas")
public class ReservaController {

    private final ReservaService reservaService;

    public ReservaController(ReservaService reservaService) {
        this.reservaService = reservaService;
    }

    @GetMapping
    public List<Reserva> listar(
            @RequestParam(required = false) String usuarioId,
            @RequestParam(required = false) String usuario,
            @RequestParam(defaultValue = "false") boolean soloActivas) {
        String filtroUsuario = (usuarioId != null && !usuarioId.isBlank()) ? usuarioId : usuario;
        return reservaService.listar(filtroUsuario, soloActivas);
    }

    @GetMapping("/disponibilidad")
    public Map<String, Object> disponibilidad(
            @RequestParam(required = false) String inicio,
            @RequestParam(required = false) String fin) {
        Instant ini = inicio != null
                ? ReservaService.parsearFechaHora(inicio, "inicio")
                : Instant.now();
        Instant fn = fin != null
                ? ReservaService.parsearFechaHora(fin, "fin")
                : ini.plus(Duration.ofHours(2));
        return reservaService.consultarDisponibilidad(ini, fn);
    }

    @PostMapping
    public ResponseEntity<Reserva> crear(@RequestBody Map<String, Object> body) {
        String usuarioId = texto(body.get("usuarioId"));
        if (usuarioId == null || usuarioId.isBlank()) {
            usuarioId = texto(body.get("usuario"));
        }

        Integer puesto = null;
        if (body.get("puesto") != null && !String.valueOf(body.get("puesto")).isBlank()) {
            try {
                puesto = Integer.valueOf(String.valueOf(body.get("puesto")).trim());
            } catch (NumberFormatException e) {
                throw new org.springframework.web.server.ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "El campo puesto debe ser un numero entero");
            }
        }

        String sede = texto(body.get("sede"));
        Instant inicio = ReservaService.parsearFechaHora(texto(body.get("inicio")), "inicio");
        Instant fin = ReservaService.parsearFechaHora(texto(body.get("fin")), "fin");

        // Si no se envia inicio/fin explicitos, por defecto reserva desde ahora por 2 horas
        if (inicio == null) {
            inicio = Instant.now();
        }
        if (fin == null) {
            fin = inicio.plus(Duration.ofHours(2));
        }

        Reserva creada = reservaService.crearReserva(usuarioId, puesto, sede, inicio, fin);
        return ResponseEntity.status(HttpStatus.CREATED).body(creada);
    }

    @DeleteMapping("/{id}")
    public Reserva cancelar(@PathVariable Long id) {
        return reservaService.cancelarReserva(id);
    }

    private static String texto(Object valor) {
        return valor == null ? null : String.valueOf(valor);
    }
}

