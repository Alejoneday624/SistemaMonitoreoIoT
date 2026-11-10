package com.campus.monitoring.api;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Respuestas claras cuando la ruta pedida no existe.
 *
 * Antes de esto, el 404 lo resolvia ResponseStatusExceptionConverter via
 * sendError(404) y caia en el dispatch de /error: el navegador recibia la
 * pagina Whitelabel de Spring y los clientes API el JSON del BasicErrorController.
 *
 * Este advice tiene prioridad (ExceptionHandlerExceptionResolver corre antes
 * que ResponseStatusExceptionResolver) y responde segun lo que pida el cliente:
 *  - Accept: text/html (navegador) -> pagina legible con enlaces utiles
 *  - cualquier otro (curl, fetch)  -> JSON, mismo contrato que api.js espera
 */
@RestControllerAdvice
public class ErrorHandlerAdvice {

    /** URL del tablero, para enlazarla desde la pagina de error */
    @Value("${panel.url:http://localhost:8080}")
    private String urlPanel;

    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public ResponseEntity<Object> noEncontrada(Exception ex, HttpServletRequest req) {
        String ruta = req.getRequestURI();
        if (pideHtml(req)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .contentType(MediaType.TEXT_HTML)
                    .body(pagina(ruta));
        }
        Map<String, Object> cuerpo = new LinkedHashMap<>();
        cuerpo.put("timestamp", Instant.now().toString());
        cuerpo.put("status", 404);
        cuerpo.put("error", "Not Found");
        cuerpo.put("path", ruta);
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_JSON)
                .body(cuerpo);
    }

    /** El navegador envia Accept: text/html,...; curl y fetch envian comodin */
    private boolean pideHtml(HttpServletRequest req) {
        String acepta = req.getHeader("Accept");
        return acepta != null && acepta.toLowerCase().contains("text/html");
    }

    /** Pagina sencilla: que falto, a donde ir y que si existe */
    private String pagina(String ruta) {
        String segura = escapar(ruta);
        String panel = escapar(urlPanel);
        return """
                <!doctype html>
                <html lang="es">
                <head>
                  <meta charset="utf-8">
                  <title>404 - ruta no encontrada</title>
                  <style>
                    body { font-family: system-ui, sans-serif; max-width: 42rem;
                           margin: 3rem auto; padding: 0 1rem; color: #1f2937; }
                    code { background: #f3f4f6; padding: .15rem .4rem; border-radius: .3rem; }
                    a { color: #1d4ed8; }
                    li { margin: .3rem 0; }
                  </style>
                </head>
                <body>
                  <h1>404 &mdash; la ruta no existe</h1>
                  <p>El backend no tiene ningun endpoint para <code>%s</code>.</p>
                  <p><strong>Tablero:</strong> <a href="%s">%s</a></p>
                  <p><strong>Endpoints disponibles:</strong></p>
                  <ul>
                    <li><a href="/api/estado">/api/estado</a> &mdash; resumen operativo</li>
                    <li><a href="/api/sensores">/api/sensores</a> &mdash; estado de los sensores</li>
                    <li><a href="/api/mediciones">/api/mediciones</a> &mdash; ultimas mediciones</li>
                    <li><a href="/api/errores">/api/errores</a> &mdash; registro de errores</li>
                    <li><a href="/actuator/health">/actuator/health</a> &mdash; salud del servicio</li>
                  </ul>
                </body>
                </html>
                """.formatted(segura, panel, panel);
    }

    /** Escapado minimo: la ruta viene del cliente y no debe inyectar markup */
    private static String escapar(String texto) {
        return texto.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
