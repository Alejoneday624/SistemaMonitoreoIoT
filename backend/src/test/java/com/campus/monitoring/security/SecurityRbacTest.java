package com.campus.monitoring.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.campus.monitoring.StackSpringTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Pruebas de integracion para Tarea 7: Gestion de usuarios y roles (RBAC — HDU-10, HDU-11, E5/E6).
 *
 * Hereda de StackSpringTest sin anotaciones adicionales de contexto para reutilizar
 * el unico contexto de Spring (y el unico MqttBus) de toda la suite.
 *
 * La identificacion en /api/admin/** es SOLO por Authorization: Basic; las
 * cabeceras declarativas (X-Usuario / X-Rol) fueron eliminadas porque permitian
 * hacerse pasar por ADMIN sin credenciales.
 */
class SecurityRbacTest extends StackSpringTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private RbacFilter rbacFilter;

    @Autowired
    private UsuarioRepository usuarios;

    private MockMvc mvc;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void configurarMockMvc() {
        this.mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(rbacFilter)
                .build();
    }

    /** Cabecera Authorization: Basic generada para las pruebas */
    private static String basic(String usuario, String clave) {
        return "Basic " + Base64.getEncoder()
                .encodeToString((usuario + ":" + clave).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void usuarioSinRolAdminRecibe403EnEndpointsAdmin() throws Exception {
        // 1. Sin cabecera de autenticacion -> 403 Forbidden
        mvc.perform(get("/api/admin/usuarios"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));

        // 2. Basic valido de usuario rol COMUNIDAD -> 403 Forbidden
        mvc.perform(get("/api/admin/usuarios")
                        .header("Authorization", basic("comunidad", "comunidad123")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.rolActual").value("COMUNIDAD"));

        // 3. Basic valido de usuario rol MANTENIMIENTO -> 403 Forbidden
        mvc.perform(get("/api/admin/usuarios")
                        .header("Authorization", basic("mantenimiento", "mant123")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.rolActual").value("MANTENIMIENTO"));

        // 4. Cabeceras declarativas antiguas (X-Rol / X-Usuario) -> 403:
        //    ya no autentican, era una puerta trasera sin credenciales.
        mvc.perform(post("/api/admin/usuarios")
                        .header("X-Rol", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"intruso\",\"password\":\"x\",\"rol\":\"ADMIN\"}"))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/admin/usuarios").header("X-Usuario", "admin"))
                .andExpect(status().isForbidden());

        // 5. Basic con password incorrecto -> 403
        mvc.perform(get("/api/admin/usuarios")
                        .header("Authorization", basic("admin", "no-es-la-clave")))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminPuedeListarCrearYCambiarRolDeUsuarios() throws Exception {
        // 1. Admin lista los usuarios semilla (HDU-10)
        mvc.perform(get("/api/admin/usuarios")
                        .header("Authorization", basic("admin", "admin123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());

        // 2. Admin crea un nuevo usuario con rol COMUNIDAD (HDU-10)
        String bodyCrear = """
                {
                  "username": "juan.perez",
                  "nombre": "Juan Perez",
                  "email": "juan@campus.edu.co",
                  "password": "claveSegura",
                  "rol": "COMUNIDAD"
                }
                """;
        String respuestaCrear = mvc.perform(post("/api/admin/usuarios")
                        .header("Authorization", basic("admin", "admin123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyCrear))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("juan.perez"))
                .andExpect(jsonPath("$.rol").value("COMUNIDAD"))
                .andReturn().getResponse().getContentAsString();

        JsonNode creado = json.readTree(respuestaCrear);
        long idUsuario = creado.get("id").asLong();

        // Mientras es COMUNIDAD, no puede entrar a /api/admin/usuarios -> 403
        mvc.perform(get("/api/admin/usuarios")
                        .header("Authorization", basic("juan.perez", "claveSegura")))
                .andExpect(status().isForbidden());

        // 3. Admin cambia su rol a MANTENIMIENTO (HDU-11)
        mvc.perform(put("/api/admin/usuarios/" + idUsuario + "/rol")
                        .header("Authorization", basic("admin", "admin123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rol\":\"MANTENIMIENTO\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rol").value("MANTENIMIENTO"));

        // 4. Admin lo promueve a ADMIN (HDU-11) y ahora si tiene acceso 200 OK
        mvc.perform(put("/api/admin/usuarios/" + idUsuario + "/rol")
                        .header("Authorization", basic("admin", "admin123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rol\":\"ADMIN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rol").value("ADMIN"));

        mvc.perform(get("/api/admin/usuarios")
                        .header("Authorization", basic("juan.perez", "claveSegura")))
                .andExpect(status().isOk());

        // 5. Login valida credenciales y devuelve el rol actualizado
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"juan.perez\",\"password\":\"claveSegura\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rol").value("ADMIN"));

        // 6. No puede autoeliminarse (queda el admin semilla, pero el propio
        //    usuario no debe poder borrarse a si mismo) -> 409
        mvc.perform(delete("/api/admin/usuarios/" + idUsuario)
                        .header("Authorization", basic("juan.perez", "claveSegura")))
                .andExpect(status().isConflict());

        // 7. El admin si lo elimina -> 204
        mvc.perform(delete("/api/admin/usuarios/" + idUsuario)
                        .header("Authorization", basic("admin", "admin123")))
                .andExpect(status().isNoContent());

        assertThat(usuarios.findById(idUsuario)).isEmpty();
    }

    @Test
    void noSePuedeEliminarAlUltimoAdministradorActivo() throws Exception {
        // Listar usuarios y quedarse con todos los ADMIN distintos del semilla
        String lista = mvc.perform(get("/api/admin/usuarios")
                        .header("Authorization", basic("admin", "admin123")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode todos = json.readTree(lista);
        long admins = 0;
        long idAdminSemilla = -1;
        for (JsonNode u : todos) {
            if ("ADMIN".equals(u.get("rol").asText()) && u.get("activo").asBoolean()) {
                admins++;
                idAdminSemilla = u.get("id").asLong();
            }
        }
        // Solo existe el admin semilla -> no se puede borrar
        assertThat(admins).isEqualTo(1);

        mvc.perform(delete("/api/admin/usuarios/" + idAdminSemilla)
                        .header("Authorization", basic("admin", "admin123")))
                .andExpect(status().isConflict());

        assertThat(usuarios.findById(idAdminSemilla)).isPresent();
    }
}
