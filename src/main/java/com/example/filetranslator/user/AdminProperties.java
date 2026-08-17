/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.filetranslator.user;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Dane konta administratora zakładanego przy starcie (prefiks app.admin).
 *
 * Jako jedyny rekord konfiguracyjny w tym projekcie NIE ma @Validated, i to z dwóch
 * niezależnych powodów - oba są istotne, więc dopisanie tej adnotacji "dla spójności"
 * zepsułoby dwie rzeczy naraz:
 *
 * 1. Walidacja rekordu konfiguracyjnego działa przy KAŻDYM starcie, także wtedy, gdy
 *    bootstrap jest wyłączony. Każdy, kto nie zakłada konta administratora, musiałby
 *    mimo to podać hasło zgodne z polityką, żeby aplikacja w ogóle wstała.
 * 2. Komunikat BindValidationException powstaje z ObjectError.toString(), a FieldError
 *    dokłada tam rejectedValue - czyli JAWNE HASŁO trafiłoby do raportu błędu startu
 *    i do logów. Walidacja siedzi więc w AdminBootstrap, gdzie panujemy nad tym,
 *    co wchodzi do komunikatu.
 *
 * Imienia tu nie ma świadomie: to napis wyświetlany na koncie technicznym, a nie
 * czwarte pokrętło konfiguracji. Stała siedzi w AdminBootstrap.
 */
@ConfigurationProperties(prefix = "app.admin")
public record AdminProperties(

        /**
         * Czy przy starcie zakładać konto administratora. Domyślnie false - konto
         * z dostępem do /actuator/** nie powstaje przez przypadek, tylko dlatego,
         * że ktoś je świadomie włączył.
         */
        boolean enabled,

        /** Adres konta administratora. Wymagany, gdy enabled = true. */
        String email,

        /**
         * Hasło jawne, hashowane BCryptem dopiero przy zakładaniu konta. Wymagane,
         * gdy enabled = true, i sprawdzane tą samą polityką co hasło z rejestracji.
         */
        String password
) {
}
