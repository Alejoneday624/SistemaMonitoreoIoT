package com.campus.monitoring.api;

import java.net.URI;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Portada del backend (GET /).
 *
 * Sin este controlador, abrir http://localhost:8081/ devolvia la pagina
 * Whitelabel de Spring ("This application has no explicit mapping for
 * /error... type=Not Found, status=404"). Ahora la raiz redirige al
 * tablero React, que es la entrada real del sistema.
 */
@RestController
public class PortadaController {

    /** URL del tablero (nginx del frontend). Se sobreescribe con panel.url */
    @Value("${panel.url:http://localhost:8080}")
    private String urlPanel;

    /** GET / -> 302 al tablero */
    @GetMapping("/")
    public ResponseEntity<Void> portada() {
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(urlPanel))
                .build();
    }
}
