package com.campus.monitoring.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
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
        if (password == null || password.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El campo password es obligatorio (ya no se asigna una clave por defecto)");
        }
        String nombreFinal = (nombre == null || nombre.isBlank()) ? limpio : nombre.trim();
        String claveFinal = password;
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

    /**
     * Elimina un usuario aplicando dos salvaguardas:
     *  1. Nadie puede eliminarse a si mismo (evita quedarse sin ADMIN operativo).
     *  2. No se puede eliminar al ultimo administrador ACTIVO del sistema.
     *
     * @param solicitante username autenticado en la peticion (atributo rbacUsuario), o null
     */
    @Transactional
    public void eliminar(Long id, String solicitante) {
        Usuario objetivo = usuarios.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuario no encontrado: " + id));

        if (solicitante != null && solicitante.equalsIgnoreCase(objetivo.getUsername())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "No puedes eliminar tu propio usuario");
        }

        if (objetivo.getRol() == Rol.ADMIN) {
            boolean quedaOtroAdminActivo = usuarios.findAllByOrderByIdAsc().stream()
                    .anyMatch(u -> !id.equals(u.getId()) && u.getRol() == Rol.ADMIN && u.isActivo());
            if (!quedaOtroAdminActivo) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "No se puede eliminar al ultimo administrador activo del sistema");
            }
        }

        usuarios.deleteById(id);
    }

    /**
     * Autentica contra la BD. Acepta hashes actuales (SHA-256 con salt, formato
     * "salt$hash") y hashes legados sin salt generados por versiones anteriores;
     * si la clave es correcta y el hash es legado, se re-escriba con salt.
     */
    @Transactional
    public Optional<Usuario> autenticar(String username, String password) {
        if (username == null || password == null) {
            return Optional.empty();
        }
        Optional<Usuario> encontrado = buscarPorUsername(username).filter(Usuario::isActivo);
        if (encontrado.isEmpty()) {
            return Optional.empty();
        }
        Usuario u = encontrado.get();
        if (!verificarPassword(password, u.getPasswordHash())) {
            return Optional.empty();
        }
        // Migracion transparente del hash antiguo (sin salt) al formato salteado
        if (!u.getPasswordHash().contains("$")) {
            u.setPasswordHash(hashPassword(password));
            usuarios.save(u);
        }
        return Optional.of(u);
    }

    /** Tamanio del salt en bytes (32 caracteres hex en el hash) */
    private static final int SALT_BYTES = 16;

    /**
     * SHA-256 con salt aleatorio: devuelve "saltHex$hashHex" (32+1+64 = 97 <= 128
     * de la columna password_hash).
     */
    public static String hashPassword(String raw) {
        byte[] salt = new byte[SALT_BYTES];
        new SecureRandom().nextBytes(salt);
        return HexFormat.of().formatHex(salt) + "$" + sha256Hex(salt, raw);
    }

    /**
     * Verifica una clave en claro contra el hash almacenado. Soporta el formato
     * salteado ("salt$hash") y el legado (solo hash hex sin salt).
     * La comparacion es en tiempo constante.
     */
    public static boolean verificarPassword(String raw, String almacenado) {
        if (raw == null || almacenado == null || almacenado.isBlank()) {
            return false;
        }
        int separador = almacenado.indexOf('$');
        if (separador > 0) {
            byte[] salt = HexFormat.of().parseHex(almacenado.substring(0, separador));
            String esperado = almacenado.substring(separador + 1);
            return MessageDigest.isEqual(esperado.getBytes(StandardCharsets.UTF_8),
                    sha256Hex(salt, raw).getBytes(StandardCharsets.UTF_8));
        }
        // Hash legado: SHA-256 sin salt (version anterior del proyecto)
        return MessageDigest.isEqual(almacenado.getBytes(StandardCharsets.UTF_8),
                sha256Hex(null, raw).getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256Hex(byte[] salt, String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            if (salt != null) {
                md.update(salt);
            }
            byte[] digest = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }
}

