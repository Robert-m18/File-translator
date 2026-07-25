/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.robert.common.security;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;

/**
 * Limity liczby żądań dla wybranych ścieżek (prefiks app.rate-limit).
 *
 * Reguły siedzą w konfiguracji, a nie w kodzie, bo różnią się między środowiskami
 * (na testach muszą być wyłączone, żeby wyniki były powtarzalne) i bo dostrajanie
 * ich po wdrożeniu nie powinno wymagać przebudowy aplikacji.
 */
@Validated
@ConfigurationProperties(prefix = "app.rate-limit")
public record RateLimitProperties(

        boolean enabled,

        /** Gdzie trzymany jest stan limitów. Szczegóły i konsekwencje: BucketProvider. */
        @NotNull Store store,

        /** Adres Redisa w formacie redis://host:port. Wymagany wyłącznie przy store=redis. */
        String redisUrl,

        @Valid List<Policy> policies
) {

    public enum Store {
        /** Pamięć procesu. Poprawne tylko dla pojedynczej instancji aplikacji. */
        MEMORY,
        /** Wspólny magazyn dla wszystkich instancji - wymagany przy skalowaniu poziomym. */
        REDIS
    }

    /**
     * @param path     wzorzec ścieżki w składni Ant (np. /auth/login, /auth/**)
     * @param capacity ile żądań mieści się w oknie
     * @param period   długość okna, po którym pula odnawia się w całości
     */
    public record Policy(
            @NotBlank String path,
            @Positive int capacity,
            @NotNull Duration period
    ) {
    }
}
