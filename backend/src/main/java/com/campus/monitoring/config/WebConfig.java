package com.campus.monitoring.config;

import java.util.Arrays;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS configurable para el despliegue Render + Vercel.
 *
 * Con los rewrites de frontend/vercel.json el tablero llama al backend en la
 * MISMA origen y esto no interviene. Solo hace falta si el frontend define
 * VITE_API_URL y pide al backend directamente: ahi el navegador exige
 * cabeceras CORS y hay que abrir explicitamente esos origenes.
 *
 * panel.cors-origins: lista separada por coma (admite comodines como
 * https://*.vercel.app). Por defecto: Vercel y el Vite de desarrollo.
 */
@Configuration
public class WebConfig {

    private final String[] origenes;

    public WebConfig(@Value("${panel.cors-origins:https://*.vercel.app,http://localhost:5173}")
                     String origenCsv) {
        this.origenes = Arrays.stream(origenCsv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);
    }

    @Bean
    public WebMvcConfigurer corsPermitido() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registro) {
                registro.addMapping("/api/**")
                        .allowedOriginPatterns(origenes)
                        .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                        .allowedHeaders("*");
            }
        };
    }
}
