/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.robert.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Dokumentacja OpenAPI 3 generowana z kodu (springdoc).
 *
 * Dokumentacja pisana ręcznie zawsze rozjeżdża się z implementacją. Tutaj opis
 * powstaje ze zbioru kontrolerów i DTO, więc rozjazd jest w praktyce niemożliwy.
 * Swagger UI: /swagger-ui.html, surowa specyfikacja: /v3/api-docs
 * (na profilu prod jedno i drugie wyłączone - patrz application-prod.yml).
 */
@Configuration
public class OpenApiConfig {

    private static final String COOKIE_AUTH = "cookieAuth";

    @Bean
    public OpenAPI apiDefinition() {
        return new OpenAPI()
                .info(new Info()
                        .title("File Translator API")
                        .version("v1")
                        .description("""
                                REST API do uwierzytelniania i zarządzania użytkownikami
                                (docelowo także tłumaczenia plików).

                                Uwierzytelnianie działa na ciasteczkach httpOnly, nie na nagłówku
                                Authorization: po `POST /auth/login` serwer ustawia ciasteczka
                                `accessToken` (15 min) oraz `refreshToken` (7 dni, ograniczone
                                ścieżką do `/auth/refresh`). Ciasteczka nie są dostępne
                                dla JavaScriptu, więc w Swagger UI trzeba włączyć "with credentials".

                                Błędy zwracane są w formacie RFC 9457 (application/problem+json)
                                z dodatkowymi polami `code`, `timestamp` i `traceId`.
                                """)
                        .contact(new Contact().name("Robert Moczygęba"))
                        .license(new License().name("Proprietary")))
                .servers(List.of(new Server().url("/").description("Bieżący serwer")))
                .components(new Components().addSecuritySchemes(COOKIE_AUTH,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.COOKIE)
                                .name("accessToken")
                                .description("Token dostępowy JWT w ciasteczku httpOnly")))
                .addSecurityItem(new SecurityRequirement().addList(COOKIE_AUTH));
    }
}
