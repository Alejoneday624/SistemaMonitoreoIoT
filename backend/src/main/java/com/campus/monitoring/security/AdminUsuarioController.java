package com.campus.monitoring.security;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Endpoints de administracion de usuarios y roles (Tarea 7 — HDU-10, HDU-11, E5/E6).
 *
 * Todas las rutas bajo /api/admin/** estan protegidas por RbacFilter y exigen
 * el rol ADMIN (usuarios sin rol ADMIN reciben 403 Forbidden).
 */
@RestController
@RequestMapping("/api")
public class AdminUsuarioController {

    private final UsuarioService usuarioService;

    public AdminUsuarioController(UsuarioService usuarioService) {
        this.usuarioService = usuarioService;
    }

    /** HDU-10: listar todos los usuarios y sus roles */
    @GetMapping("/admin/usuarios")
    public List<Usuario> listarUsuarios() {
        return usuarioService.listarTodos();
    }

    /** HDU-10: crear un nuevo usuario asignandole un rol (ADMIN / MANTENIMIENTO / COMUNIDAD) */
    @PostMapping("/admin/usuarios")
    public ResponseEntity<Usuario> crearUsuario(@RequestBody Map<String, Object> body) {
        String username = texto(body.get("username"));
        String nombre = texto(body.get("nombre"));
        String email = texto(body.get("email"));
        String password = texto(body.get("password"));
        String rol = texto(body.get("rol"));

        Usuario creado = usuarioService.crearUsuario(username, nombre, email, password, rol);
        return ResponseEntity.status(HttpStatus.CREATED).body(creado);
    }

    /** HDU-11: modificar el rol de un usuario existente */
    @PutMapping("/admin/usuarios/{id}/rol")
    public Usuario cambiarRol(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        String nuevoRol = texto(body.get("rol"));
        return usuarioService.cambiarRol(id, nuevoRol);
    }

    /** HDU-10/HDU-11: activar o desactivar un usuario */
    @PutMapping("/admin/usuarios/{id}/estado")
    public Usuario cambiarEstado(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Object activoObj = body.get("activo");
        if (activoObj == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El campo activo (boolean) es obligatorio");
        }
        boolean activo = Boolean.parseBoolean(String.valueOf(activoObj));
        return usuarioService.cambiarEstado(id, activo);
    }

    /** Eliminar un usuario por ID */
    @DeleteMapping("/admin/usuarios/{id}")
    public ResponseEntity<Void> eliminarUsuario(@PathVariable Long id) {
        usuarioService.eliminar(id);
        return ResponseEntity.noContent().build();
    }

    /** Catalogo de roles disponibles en el sistema */
    @GetMapping("/admin/roles")
    public List<String> listarRoles() {
        return List.of(Rol.ADMIN.name(), Rol.MANTENIMIENTO.name(), Rol.COMUNIDAD.name());
    }

    /** Endpoint de login para validar credenciales y consultar el rol asignado */
    @PostMapping("/auth/login")
    public Map<String, Object> login(@RequestBody Map<String, Object> body) {
        String username = texto(body.get("username"));
        String password = texto(body.get("password"));
        Usuario u = usuarioService.autenticar(username, password)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Credenciales invalidas o usuario inactivo"));

        Map<String, Object> respuesta = new LinkedHashMap<>();
        respuesta.put("id", u.getId());
        respuesta.put("username", u.getUsername());
        respuesta.put("nombre", u.getNombre());
        respuesta.put("rol", u.getRol().name());
        respuesta.put("activo", u.isActivo());
        return respuesta;
    }

    private static String texto(Object valor) {
        return valor == null ? null : String.valueOf(valor);
    }
}

