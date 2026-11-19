package com.campus.monitoring.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Filtro RBAC (Control de Acceso Basado en Roles — HDU-10, HDU-11, E5/E6).
 *
 * Protege los endpoints administrativos (/api/admin/**):
 * - Cualquier peticion sin rol ADMIN recibe HTTP 403 Forbidden.
 * - La UNICA forma de identificarse es Authorization: Basic base64(username:password),
 *   validado contra la base de datos (usuario existente, activo y con rol ADMIN).
 *
 * Por seguridad NO se aceptan cabeceras declarativas (X-Rol / X-Usuario): cualquiera
 * podria enviarlas desde Postman y hacerse pasar por ADMIN sin credenciales.
 * El usuario autenticado se expone al resto de la cadena como atributo de peticion
 * (rbacUsuario) para que los controladores puedan aplicar reglas adicionales
 * (p. ej. no autoeliminarse).
 *
 * El resto de rutas publicas (/api/estado, /api/sensores, /api/mediciones,
 * /api/errores, /api/reservas, /api/demo/**, /api/auth/login) permanecen
 * accesibles para no interferir con el tablero ni los demas miembros.
 */
@Component
public class RbacFilter extends OncePerRequestFilter {

    /** Atributo de peticion con el username autenticado (rol ADMIN) */
    public static final String ATTR_USUARIO = "rbacUsuario";

    private final UsuarioService usuarioService;
    private final ObjectMapper json = new ObjectMapper();

    public RbacFilter(UsuarioService usuarioService) {
        this.usuarioService = usuarioService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {

        // Permitir preflight CORS sin bloqueo
        if ("OPTIONS".equalsIgnoreCase(req.getMethod())) {
            chain.doFilter(req, res);
            return;
        }

        String ruta = req.getRequestURI();
        if (ruta != null && ruta.startsWith("/api/admin")) {
            Usuario autenticado = autenticarBasico(req);
            if (autenticado == null || autenticado.getRol() != Rol.ADMIN) {
                escribirProhibido(req, res, autenticado == null ? null : autenticado.getRol());
                return;
            }
            req.setAttribute(ATTR_USUARIO, autenticado.getUsername());
        }

        chain.doFilter(req, res);
    }

    /**
     * Identifica al solicitante unicamente con Authorization: Basic.
     * Devuelve null si no hay cabecera, esta mal formada o las credenciales
     * no corresponden a un usuario existente y activo.
     */
    private Usuario autenticarBasico(HttpServletRequest req) {
        String auth = req.getHeader("Authorization");
        if (auth == null || !auth.regionMatches(true, 0, "Basic ", 0, 6)) {
            return null;
        }
        try {
            String b64 = auth.substring(6).trim();
            String decodificado = new String(Base64.getDecoder().decode(b64), StandardCharsets.UTF_8);
            int idx = decodificado.indexOf(':');
            if (idx <= 0) {
                return null;
            }
            String user = decodificado.substring(0, idx);
            String pass = decodificado.substring(idx + 1);
            return usuarioService.autenticar(user, pass).orElse(null);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void escribirProhibido(HttpServletRequest req, HttpServletResponse res, Rol rolDetectado)
            throws IOException {
        res.setStatus(HttpStatus.FORBIDDEN.value());
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setCharacterEncoding(StandardCharsets.UTF_8.name());

        Map<String, Object> cuerpo = new LinkedHashMap<>();
        cuerpo.put("timestamp", Instant.now().toString());
        cuerpo.put("status", HttpStatus.FORBIDDEN.value());
        cuerpo.put("error", "Forbidden");
        cuerpo.put("message", "Acceso denegado: se requiere autenticacion Basic con rol ADMIN para acceder a este recurso");
        cuerpo.put("rolActual", rolDetectado == null ? "NINGUNO" : rolDetectado.name());
        cuerpo.put("path", req.getRequestURI());

        res.getWriter().write(json.writeValueAsString(cuerpo));
    }
}
