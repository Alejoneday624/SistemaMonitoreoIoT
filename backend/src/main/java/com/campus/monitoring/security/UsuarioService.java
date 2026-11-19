package com.campus.monitoring.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Logica de negocio para gestion de usuarios y roles (HDU-10, HDU-11, E5/E6).
 *
 * Al iniciar la aplicacion garantiza tres usuarios semilla (admin,
 * mantenimiento, comunidad) para que las pruebas y demostraciones funcionen
 * sin configuracion manual previa.
 */
@Service
public class UsuarioService {

    private static final Logger log = LoggerFactory.getLogger(UsuarioService.class);

    private final UsuarioRepository usuarios;

    public UsuarioService(UsuarioRepository usuarios) {
        this.usuarios = usuarios;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void inicializarUsuariosSemilla() {
        asegurarSemilla("admin", "Administrador General", "admin@campus.edu.co", "admin123", Rol.ADMIN);
        asegurarSemilla("mantenimiento", "Técnico de Mantenimiento", "mantenimiento@campus.edu.co", "mant123", Rol.MANTENIMIENTO);
        asegurarSemilla("comunidad", "Estudiante / Comunidad", "comunidad@campus.edu.co", "comunidad123", Rol.COMUNIDAD);
        log.info("[SECURITY] Usuarios RBAC verificados ({} en total)", usuarios.count());
    }

    private void asegurarSemilla(String username, String nombre, String email, String clave, Rol rol) {
        if (!usuarios.existsByUsernameIgnoreCase(username)) {
            usuarios.save(new Usuario(username, nombre, email, hashPassword(clave), rol));
        }
    }

    public List<Usuario> listarTodos() {
        return usuarios.findAllByOrderByIdAsc();
    }

    public Optional<Usuario> buscarPorUsername(String username) {
        if (username == null || username.isBlank()) {
            return Optional.empty();
        }
        return usuarios.findByUsernameIgnoreCase(username.trim());
    }

    @Transactional
    public Usuario crearUsuario(String username, String nombre, String email, String password, String rolTexto) {
        if (username == null || username.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El campo username es obligatorio");
        }
        String limpio = username.trim();
        if (usuarios.existsByUsernameIgnoreCase(limpio)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ya existe un usuario con username: " + limpio);
        }
        Rol rol = Rol.desdeTexto(rolTexto);
        if (rol == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Rol invalido. Valores permitidos: ADMIN, MANTENIMIENTO, COMUNIDAD");
        }
        String nombreFinal = (nombre == null || nombre.isBlank()) ? limpio : nombre.trim();
        String claveFinal = (password == null || password.isBlank()) ? "campus123" : password;
        Usuario nuevo = new Usuario(limpio, nombreFinal, email, hashPassword(claveFinal), rol);
        return usuarios.save(nuevo);
    }

    @Transactional
    public Usuario cambiarRol(Long id, String nuevoRolTexto) {
        Rol nuevoRol = Rol.desdeTexto(nuevoRolTexto);
        if (nuevoRol == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Rol invalido. Valores permitidos: ADMIN, MANTENIMIENTO, COMUNIDAD");
        }
        Usuario u = usuarios.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuario no encontrado: " + id));
        u.setRol(nuevoRol);
        return usuarios.save(u);
    }

    @Transactional
    public Usuario cambiarEstado(Long id, boolean activo) {
        Usuario u = usuarios.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuario no encontrado: " + id));
        u.setActivo(activo);
        return usuarios.save(u);
    }

    @Transactional
    public void eliminar(Long id) {
        if (!usuarios.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuario no encontrado: " + id);
        }
        usuarios.deleteById(id);
    }

    public Optional<Usuario> autenticar(String username, String password) {
        if (username == null || password == null) {
            return Optional.empty();
        }
        return buscarPorUsername(username)
                .filter(Usuario::isActivo)
                .filter(u -> u.getPasswordHash().equals(hashPassword(password)));
    }

    public static String hashPassword(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }
}

