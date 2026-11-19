package com.campus.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.campus.monitoring.api.PanelApiController;
import com.campus.monitoring.persistencia.ErrorRegistro;
import com.campus.monitoring.persistencia.ErrorRegistroRepository;
import com.campus.monitoring.persistencia.MedicionRepository;
import com.campus.monitoring.persistencia.SensorRepository;
import com.campus.monitoring.reservas.Reserva;
import com.campus.monitoring.reservas.ReservaController;
import com.campus.monitoring.reservas.ReservaRepository;
import com.campus.monitoring.reservas.ReservaService;
import com.campus.monitoring.security.AdminUsuarioController;
import com.campus.monitoring.security.RbacFilter;
import com.campus.monitoring.security.Rol;
import com.campus.monitoring.security.Usuario;
import com.campus.monitoring.security.UsuarioRepository;
import com.campus.monitoring.security.UsuarioService;
import com.campus.monitoring.sensor.SensorSimulator;

/**
 * Prueba funcional autonoma (sin requerir Docker) para verificar de extremo a
 * extremo las Tareas 7, 8 y 9 de la Persona D a traves de MockMvc:
 *  - Tarea 7: RBAC (ADMIN / MANTENIMIENTO / COMUNIDAD), 403 a no-admin y CRUD en /api/admin/**
 *  - Tarea 8: Reservas de biblioteca (tope de 50 cupos, rechazo de la 51ava y sin solapamientos)
 *  - Tarea 9: Filtros combinables en GET /api/errores (tipo, sensor/sensorId, fecha, desde, hasta)
 */
class PersonaDStandaloneTest {

    private final List<Usuario> bdUsuarios = new ArrayList<>();
    private final List<Reserva> bdReservas = new ArrayList<>();
    private final List<ErrorRegistro> bdErrores = new ArrayList<>();
    private final AtomicLong seqUsuario = new AtomicLong(1);
    private final AtomicLong seqReserva = new AtomicLong(1);

    private MockMvc mvc;

    @BeforeEach
    void prepararEntorno() {
        bdUsuarios.clear();
        bdReservas.clear();
        bdErrores.clear();

        // 1. Repositorio en memoria para Usuarios (Tarea 7)
        UsuarioRepository usuarioRepo = mock(UsuarioRepository.class);
        when(usuarioRepo.save(any(Usuario.class))).thenAnswer(inv -> {
            Usuario u = inv.getArgument(0);
            if (u.getId() == null) {
                u.setId(seqUsuario.getAndIncrement());
                bdUsuarios.add(u);
            }
            return u;
        });
        when(usuarioRepo.findAllByOrderByIdAsc()).thenAnswer(inv -> new ArrayList<>(bdUsuarios));
        when(usuarioRepo.existsByUsernameIgnoreCase(any())).thenAnswer(inv -> {
            String username = inv.getArgument(0);
            return bdUsuarios.stream().anyMatch(u -> u.getUsername().equalsIgnoreCase(username));
        });
        when(usuarioRepo.findByUsernameIgnoreCase(any())).thenAnswer(inv -> {
            String username = inv.getArgument(0);
            return bdUsuarios.stream().filter(u -> u.getUsername().equalsIgnoreCase(username)).findFirst();
        });
        when(usuarioRepo.findById(any())).thenAnswer(inv -> {
            Long id = inv.getArgument(0);
            return bdUsuarios.stream().filter(u -> id.equals(u.getId())).findFirst();
        });
        when(usuarioRepo.existsById(any())).thenAnswer(inv -> {
            Long id = inv.getArgument(0);
            return bdUsuarios.stream().anyMatch(u -> id.equals(u.getId()));
        });
        doAnswer(inv -> {
            Long id = inv.getArgument(0);
            bdUsuarios.removeIf(u -> id.equals(u.getId()));
            return null;
        }).when(usuarioRepo).deleteById(any());

        UsuarioService usuarioService = new UsuarioService(usuarioRepo);
        usuarioService.inicializarUsuariosSemilla();
        RbacFilter rbacFilter = new RbacFilter(usuarioService);
        AdminUsuarioController adminController = new AdminUsuarioController(usuarioService);

        // 2. Repositorio en memoria para Reservas (Tarea 8)
        ReservaRepository reservaRepo = mock(ReservaRepository.class);
        when(reservaRepo.save(any(Reserva.class))).thenAnswer(inv -> {
            Reserva r = inv.getArgument(0);
            if (r.getId() == null) {
                r.setId(seqReserva.getAndIncrement());
                bdReservas.add(r);
            }
            return r;
        });
        when(reservaRepo.findById(any())).thenAnswer(inv -> {
            Long id = inv.getArgument(0);
            return bdReservas.stream().filter(r -> id.equals(r.getId())).findFirst();
        });
        when(reservaRepo.findAllByOrderByInicioDesc()).thenAnswer(inv -> new ArrayList<>(bdReservas));
        when(reservaRepo.contarActivasSolapadas(any(), any())).thenAnswer(inv -> {
            Instant ini = inv.getArgument(0);
            Instant fin = inv.getArgument(1);
            return bdReservas.stream()
                    .filter(r -> Reserva.ESTADO_ACTIVA.equals(r.getEstado()))
                    .filter(r -> r.getInicio().isBefore(fin) && r.getFin().isAfter(ini))
                    .count();
        });
        when(reservaRepo.existeSolapamientoUsuario(any(), any(), any())).thenAnswer(inv -> {
            String uid = inv.getArgument(0);
            Instant ini = inv.getArgument(1);
            Instant fin = inv.getArgument(2);
            return bdReservas.stream()
                    .filter(r -> Reserva.ESTADO_ACTIVA.equals(r.getEstado()))
                    .filter(r -> r.getUsuarioId().equalsIgnoreCase(uid))
                    .anyMatch(r -> r.getInicio().isBefore(fin) && r.getFin().isAfter(ini));
        });
        when(reservaRepo.existeSolapamientoPuesto(any(), any(), any())).thenAnswer(inv -> {
            Integer puesto = inv.getArgument(0);
            Instant ini = inv.getArgument(1);
            Instant fin = inv.getArgument(2);
            return bdReservas.stream()
                    .filter(r -> Reserva.ESTADO_ACTIVA.equals(r.getEstado()))
                    .filter(r -> puesto != null && puesto.equals(r.getPuesto()))
                    .anyMatch(r -> r.getInicio().isBefore(fin) && r.getFin().isAfter(ini));
        });

        ReservaService reservaService = new ReservaService(reservaRepo);
        ReservaController reservaController = new ReservaController(reservaService);

        // 3. Repositorio en memoria para Errores (Tarea 9)
        ErrorRegistroRepository errorRepo = mock(ErrorRegistroRepository.class);
        when(errorRepo.buscarConFiltros(any(), any(), any(), any())).thenAnswer(inv -> {
            String tipo = inv.getArgument(0);
            String sensorId = inv.getArgument(1);
            Instant desde = inv.getArgument(2);
            Instant hasta = inv.getArgument(3);
            return bdErrores.stream()
                    .filter(e -> tipo == null
                            || (e.getTipo() != null && e.getTipo().equalsIgnoreCase(tipo)))
                    .filter(e -> sensorId == null
                            || (e.getSensorId() != null && e.getSensorId().equalsIgnoreCase(sensorId)))
                    .filter(e -> desde == null
                            || (e.getFecha() != null && !e.getFecha().isBefore(desde)))
                    .filter(e -> hasta == null
                            || (e.getFecha() != null && !e.getFecha().isAfter(hasta)))
                    .sorted(Comparator.comparing(ErrorRegistro::getFecha).reversed())
                    .toList();
        });

        PanelApiController panelController = new PanelApiController(
                mock(SensorRepository.class),
                mock(MedicionRepository.class),
                errorRepo,
                mock(SensorSimulator.class));

        this.mvc = MockMvcBuilders.standaloneSetup(adminController, reservaController, panelController)
                .addFilters(rbacFilter)
                .build();
    }

    /** Cabecera Authorization: Basic generada para las pruebas de RBAC */
    private static String basic(String usuario, String clave) {
        return "Basic " + Base64.getEncoder()
                .encodeToString((usuario + ":" + clave).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void tarea7_rbacBloqueaNoAdminYPermiteGestionCompletaAlAdmin() throws Exception {
        // Sin credenciales -> 403
        mvc.perform(get("/api/admin/usuarios"))
                .andExpect(status().isForbidden());

        // Rol COMUNIDAD (Basic valido) -> 403
        mvc.perform(get("/api/admin/usuarios")
                        .header("Authorization", basic("comunidad", "comunidad123")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.rolActual").value("COMUNIDAD"));

        // Rol MANTENIMIENTO (Basic valido) -> 403
        mvc.perform(get("/api/admin/usuarios")
                        .header("Authorization", basic("mantenimiento", "mant123")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.rolActual").value("MANTENIMIENTO"));

        // Cabeceras declarativas antiguas (X-Usuario / X-Rol) ya NO autentican:
        // cualquiera podia mandarlas desde Postman y entrar sin clave.
        mvc.perform(get("/api/admin/usuarios").header("X-Usuario", "admin"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/usuarios").header("X-Rol", "ADMIN"))
                .andExpect(status().isForbidden());

        // Rol ADMIN (Basic valido) -> 200 OK con los 3 usuarios semilla
        mvc.perform(get("/api/admin/usuarios")
                        .header("Authorization", basic("admin", "admin123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)));

        // Password incorrecto -> 403 (no se entra por fuerza bruta con Basic)
        mvc.perform(get("/api/admin/usuarios")
                        .header("Authorization", basic("admin", "clave-mala")))
                .andExpect(status().isForbidden());

        // Crear usuario y cambiarle rol con Basic Auth de admin
        mvc.perform(post("/api/admin/usuarios")
                        .header("Authorization", basic("admin", "admin123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"nuevo\",\"nombre\":\"Nuevo\",\"password\":\"clave123\",\"rol\":\"COMUNIDAD\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.rol").value("COMUNIDAD"));

        // Sin password ya no se crea (antes se asignaba "campus123" por defecto)
        mvc.perform(post("/api/admin/usuarios")
                        .header("Authorization", basic("admin", "admin123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"sin-clave\",\"rol\":\"COMUNIDAD\"}"))
                .andExpect(status().isBadRequest());

        Long nuevoId = bdUsuarios.stream()
                .filter(u -> "nuevo".equals(u.getUsername()))
                .findFirst().orElseThrow().getId();

        mvc.perform(put("/api/admin/usuarios/" + nuevoId + "/rol")
                        .header("Authorization", basic("admin", "admin123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rol\":\"ADMIN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rol").value("ADMIN"));

        assertThat(bdUsuarios).extracting(Usuario::getRol).contains(Rol.ADMIN);
    }

    @Test
    void tarea8_reservasBibliotecaPermite50YRechazaLa51avaYSolapamientos() throws Exception {
        String inicio = "2026-11-20T10:00:00Z";
        String fin = "2026-11-20T12:00:00Z";

        for (int i = 1; i <= 50; i++) {
            String json = """
                    {"usuarioId":"user-%d","puesto":%d,"inicio":"%s","fin":"%s"}
                    """.formatted(i, i, inicio, fin);
            mvc.perform(post("/api/reservas")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json))
                    .andExpect(status().isCreated());
        }

        // Reserva #51 en el mismo horario -> 409 Conflict
        mvc.perform(post("/api/reservas")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"usuarioId\":\"user-51\",\"inicio\":\"2026-11-20T11:00:00Z\",\"fin\":\"2026-11-20T13:00:00Z\"}"))
                .andExpect(status().isConflict());

        // Cancelar 1 reserva libera el cupo
        Long idPrimera = bdReservas.get(0).getId();
        mvc.perform(delete("/api/reservas/" + idPrimera))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("CANCELADA"));

        // Ahora user-51 si puede reservar
        mvc.perform(post("/api/reservas")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"usuarioId\":\"user-51\",\"inicio\":\"2026-11-20T11:00:00Z\",\"fin\":\"2026-11-20T12:00:00Z\"}"))
                .andExpect(status().isCreated());

        // Pero user-51 no puede volver a reservar solapado consigo mismo -> 409 Conflict
        mvc.perform(post("/api/reservas")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"usuarioId\":\"user-51\",\"inicio\":\"2026-11-20T11:30:00Z\",\"fin\":\"2026-11-20T12:30:00Z\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void tarea9_filtrosDeErroresPorFechaTipoYSensor() throws Exception {
        bdErrores.add(new ErrorRegistro("MEDICION_INVALIDA", "TMP-SUR-01",
                Instant.parse("2026-11-10T08:00:00Z"), "Fuera de rango"));
        bdErrores.add(new ErrorRegistro("SENSOR_INACTIVO", "TMP-SUR-01",
                Instant.parse("2026-11-12T15:00:00Z"), "Sin heartbeat"));
        bdErrores.add(new ErrorRegistro("MEDICION_INVALIDA", "AQI-FED-01",
                Instant.parse("2026-11-12T19:00:00Z"), "Valor negativo"));

        mvc.perform(get("/api/errores").param("tipo", "MEDICION_INVALIDA"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));

        mvc.perform(get("/api/errores").param("sensor", "TMP-SUR-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));

        mvc.perform(get("/api/errores").param("fecha", "2026-11-12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));

        mvc.perform(get("/api/errores")
                        .param("tipo", "MEDICION_INVALIDA")
                        .param("sensorId", "AQI-FED-01")
                        .param("fecha", "2026-11-12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].sensorId").value("AQI-FED-01"));
    }
}

