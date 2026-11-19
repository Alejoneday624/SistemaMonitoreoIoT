package com.campus.monitoring.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

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
 * - Soporta identificacion por:
 *   1. Cabecera X-Usuario (consulta el usuario activo y su rol en la BD)
 *   2. Cabecera Authorization: Basic base64(username:password)
 *   3. Cabecera X-Rol (ADMIN | MANTENIMIENTO | COMUNIDAD)
 *
 * El resto de rutas publicas (/api/estado, /api/sensores, /api/mediciones,
 * /api/errores, /api/reservas, /api/demo/**) permanecen accesibles para no
 * interferir con el flujo del tablero ni los componentes de otros miembros.
 */
@Component
public class RbacFilter extends OncePerRequestFilter {

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
            Rol rolEfectivo = resolverRol(req);
            if (rolEfectivo != Rol.ADMIN) {
                escribirProhibido(req, res, rolEfectivo);
                return;
            }
        }

        chain.doFilter(req, res);
    }

    /**
     * Resuelve el rol del solicitante a partir de las cabeceras HTTP:
     * 1) Authorization: Basic <base64(user:pass)>
     * 2) X-Usuario: <username> (verifica que exista y este activo en la BD;
     *    si no existe en BD, intenta interpretar el valor como nombre de rol)
     * 3) X-Rol: <ADMIN|MANTENIMIENTO|COMUNIDAD>
     */
    private Rol resolverRol(HttpServletRequest req) {
        String auth = req.getHeader("Authorization");
        if (auth != null && auth.regionMatches(true, 0, "Basic ", 0, 6)) {
            try {
                String b64 = auth.substring(6).trim();
                String decodificado = new String(Base64.getDecoder().decode(b64), StandardCharsets.UTF_8);
                int idx = decodificado.indexOf(':');
                if (idx > 0) {
                    String user = decodificado.substring(0, idx);
                    String pass = decodificado.substring(idx + 1);
                    Optional<Usuario> autenticado = usuarioService.autenticar(user, pass);
                    if (autenticado.isPresent()) {
                        return autenticado.get().getRol();
                    }
                    return null;
                }
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }

        String xUsuario = req.getHeader("X-Usuario");
        if (xUsuario != null && !xUsuario.isBlank()) {
            Optional<Usuario> u = usuarioService.buscarPorUsername(xUsuario);
            if (u.isPresent()) {
                return u.get().isActivo() ? u.get().getRol() : null;
            }
            return null;
        }

        String xRol = req.getHeader("X-Rol");
        if (xRol != null && !xRol.isBlank()) {
            return Rol.desdeTexto(xRol);
        }

        return null;
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
        cuerpo.put("message", "Acceso denegado: se requiere rol ADMIN para acceder a este recurso");
        cuerpo.put("rolActual", rolDetectado == null ? "NINGUNO" : rolDetectado.name());
        cuerpo.put("path", req.getRequestURI());

        res.getWriter().write(json.writeValueAsString(cuerpo));
    }
}

