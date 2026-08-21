/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.filetranslator.common.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Ustawienia ciasteczek z tokenami (prefiks app.cookie w application.yml).
 *
 * Wyniesione do konfiguracji, bo różnią się między środowiskami: lokalnie ruch idzie po HTTP
 * i atrybut Secure musi być wyłączony, bo inaczej przeglądarka odrzuci ciasteczko;
 * na produkcji jest odwrotnie i ciasteczko ma latać wyłącznie po HTTPS.
 *
 * @Validated + adnotacje walidacyjne sprawiają, że literówka w yml-u wywala
 * aplikację przy starcie, a nie dopiero przy pierwszym logowaniu.
 */
@Validated
@ConfigurationProperties(prefix = "app.cookie")
public record CookieProperties(

        /** Czy ciasteczko ma być wysyłane wyłącznie po HTTPS. Na produkcji zawsze true. */
        boolean secure,

        /** Strict | Lax | None. Strict blokuje wysyłkę ciasteczka przy przejściu z obcej domeny. */
        @NotBlank String sameSite,

        /** Ścieżka ciasteczka z refresh tokenem - zawężona, żeby nie latał przy każdym requeście. */
        @NotBlank String refreshTokenPath,

        @NotNull Duration accessTokenMaxAge,

        @NotNull Duration refreshTokenMaxAge
) {
}
