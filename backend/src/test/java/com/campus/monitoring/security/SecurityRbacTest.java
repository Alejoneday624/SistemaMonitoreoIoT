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

    @Test
    void usuarioSinRolAdminRecibe403EnEndpointsAdmin() throws Exception {
        // 1. Sin cabeceras de autenticacion -> 403 Forbidden
        mvc.perform(get("/api/admin/usuarios"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));

        // 2. Con usuario de rol COMUNIDAD -> 403 Forbidden
        mvc.perform(get("/api/admin/usuarios")
                        .header("X-Usuario", "comunidad"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.rolActual").value("COMUNIDAD"));

        // 3. Con usuario de rol MANTENIMIENTO -> 403 Forbidden
        mvc.perform(get("/api/admin/usuarios")
                        .header("X-Usuario", "mantenimiento"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.rolActual").value("MANTENIMIENTO"));

        // 4. Con cabecera X-Rol explicita no-admin -> 403 Forbidden
        mvc.perform(post("/api/admin/usuarios")
                        .header("X-Rol", "COMUNIDAD")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"intruso\",\"rol\":\"ADMIN\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminPuedeListarCrearYCambiarRolDeUsuarios() throws Exception {
        // 1. Admin lista los usuarios semilla (HDU-10)
        mvc.perform(get("/api/admin/usuarios")
                        .header("X-Usuario", "admin"))
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
                        .header("X-Usuario", "admin")
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
                        .header("X-Usuario", "juan.perez"))
                .andExpect(status().isForbidden());

        // 3. Admin cambia su rol a MANTENIMIENTO (HDU-11)
        mvc.perform(put("/api/admin/usuarios/" + idUsuario + "/rol")
                        .header("X-Usuario", "admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rol\":\"MANTENIMIENTO\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rol").value("MANTENIMIENTO"));

        // 4. Admin lo promueve a ADMIN (HDU-11) y ahora si tiene acceso 200 OK
        String basicAdmin = Base64.getEncoder()
                .encodeToString("admin:admin123".getBytes(StandardCharsets.UTF_8));
        mvc.perform(put("/api/admin/usuarios/" + idUsuario + "/rol")
                        .header("Authorization", "Basic " + basicAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rol\":\"ADMIN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rol").value("ADMIN"));

        mvc.perform(get("/api/admin/usuarios")
                        .header("X-Usuario", "juan.perez"))
                .andExpect(status().isOk());

        // 5. Login valida credenciales y devuelve el rol actualizado
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"juan.perez\",\"password\":\"claveSegura\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rol").value("ADMIN"));

        // 6. Limpieza del usuario de prueba
        mvc.perform(delete("/api/admin/usuarios/" + idUsuario)
                        .header("X-Usuario", "admin"))
                .andExpect(status().isNoContent());

        assertThat(usuarios.findById(idUsuario)).isEmpty();
    }
}

