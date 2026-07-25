/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.robert.user.dto;

import com.example.robert.common.validation.ValidEmail;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
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
         * Polityka hasła egzekwowana jest TUTAJ, na danych wejściowych.
         * Wcześniej stała na encji User jako @Size(min = 8) - a tam trafia już hash
         * BCrypt (zawsze 60 znaków), więc realnie przechodziło dowolne hasło, także "a".
         *
         * Górny limit 72 nie jest widzimisię: BCrypt ucina wejście po 72 bajtach,
         * więc dłuższe hasło daje użytkownikowi fałszywe poczucie bezpieczeństwa.
         */
        @NotBlank(message = "Hasło nie może być puste")
        @Size(min = 8, max = 72, message = "Hasło musi mieć od {min} do {max} znaków")
        @Pattern(
                regexp = "^(?=.*[a-zA-Z])(?=.*\\d).+$",
                message = "Hasło musi zawierać co najmniej jedną literę i jedną cyfrę"
        )
        String password

) {}
