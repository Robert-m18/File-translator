/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.filetranslator.common.security;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Blokada konta po serii nieudanych logowań (prefiks app.account-lock).
 *
 * Uzupełnia ograniczanie liczby żądań, a nie zastępuje go: limit per adres IP nie chroni
 * konta przed atakiem rozproszonym z wielu adresów, a blokada konta nie chroni serwera
 * przed zalewem żądań na tysiąc różnych kont. Dopiero razem pokrywają oba scenariusze.
 */
@Validated
@ConfigurationProperties(prefix = "app.account-lock")
public record AccountLockProperties(

        boolean enabled,

        /** Po ilu kolejnych nieudanych logowaniach konto zostaje zablokowane. */
        @Positive int maxAttempts,

        /** Na jak długo. Blokada wygasa sama - nie wymaga interwencji administratora. */
        @NotNull Duration lockDuration
) {
}
