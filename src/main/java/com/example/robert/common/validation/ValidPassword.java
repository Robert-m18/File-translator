/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.robert.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.lang.annotation.*;

/**
 * Polityka hasła w jednym miejscu.
 *
 * Wyniesiona do adnotacji złożonej, bo hasło przychodzi od użytkownika w dwóch różnych
 * miejscach: przy rejestracji (UserRequestDTO) i przy resecie (ResetPasswordRequest).
 * Skopiowany zestaw @Size/@Pattern rozjechałby się przy pierwszej zmianie wymagań,
 * i to w sposób cichy - reset pozwalałby ustawić hasło słabsze niż rejestracja.
 *
 * Górny limit 72 nie jest widzimisię: BCrypt ucina wejście po 72 bajtach, więc dłuższe
 * hasło daje użytkownikowi fałszywe poczucie bezpieczeństwa.
 *
 * Walidatora własnego tu nie ma - ograniczenia składowe (@NotBlank, @Size, @Pattern)
 * są stosowane przez silnik walidacji same, stąd validatedBy = {}.
 */
@NotBlank(message = "Hasło nie może być puste")
@Size(min = 8, max = 72, message = "Hasło musi mieć od {min} do {max} znaków")
@Pattern(
        regexp = "^(?=.*[a-zA-Z])(?=.*\\d).+$",
        message = "Hasło musi zawierać co najmniej jedną literę i jedną cyfrę"
)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = {})
@Documented
public @interface ValidPassword {
    String message() default "Hasło nie spełnia wymagań";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
