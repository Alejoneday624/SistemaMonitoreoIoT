package com.campus.monitoring.security;

/**
 * Roles del sistema (RBAC — HDU-10, HDU-11, escenarios E5/E6):
 * - ADMIN: gestion de usuarios, roles y configuracion general.
 * - MANTENIMIENTO: operacion tecnica de sensores y diagnostico de fallas.
 * - COMUNIDAD: consulta del tablero ambiental y reservas de biblioteca.
 */
public enum Rol {
    ADMIN,
    MANTENIMIENTO,
    COMUNIDAD;

    /**
     * Convierte un texto libre (ej. "admin", "ROLE_ADMIN", "comunidad") al enum
     * correspondiente, o devuelve null si no coincide.
     */
    public static Rol desdeTexto(String valor) {
        if (valor == null || valor.isBlank()) {
            return null;
        }
        String limpio = valor.trim().toUpperCase();
        if (limpio.startsWith("ROLE_")) {
            limpio = limpio.substring(5);
        }
        try {
            return Rol.valueOf(limpio);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}

