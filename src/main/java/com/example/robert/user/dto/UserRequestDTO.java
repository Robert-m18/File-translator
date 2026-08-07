/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.robert.user.dto;

import com.example.robert.common.validation.EmailNormalizer;
import com.example.robert.common.validation.ValidEmail;
import com.example.robert.common.validation.ValidPassword;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UserRequestDTO(

        @NotBlank(message = "Imię nie może być puste")
        @Size(min = 2, max = 50, message = "Imię musi mieć od {min} do {max} znaków")
        String name,

        @ValidEmail(message = "Niepoprawny format email")
        @NotBlank(message = "Email nie może być pusty")
        @Size(max = 255, message = "Email może mieć maksymalnie {max} znaków")
        String email,

        /*
         * Polityka hasła egzekwowana jest na danych wejściowych, nigdy na encji.
         * Na encji User stała kiedyś jako @Size(min = 8) - a tam trafia już hash BCrypt
         * (zawsze 60 znaków), więc realnie przechodziło dowolne hasło, także "a".
         *
         * Same reguły siedzą w @ValidPassword, bo hasło przychodzi też przy resecie
         * (ResetPasswordRequest) i dwa skopiowane zestawy adnotacji by się rozjechały.
         */
        @ValidPassword
        String password

) {
    /*
     * Postać kanoniczna adresu już na granicy aplikacji - patrz EmailNormalizer.
     * Tu ma to dodatkowe znaczenie: adres z tego DTO trafia do poczekalni rejestracji
     * i stamtąd wprost do kolumny users.email, więc to on decyduje, w jakiej postaci
     * adres wyląduje w bazie i czy unikat uk_users_email faktycznie działa.
     */
    public UserRequestDTO {
        email = EmailNormalizer.normalize(email);
    }
}
