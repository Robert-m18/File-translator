/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.robert.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Powód blokady konta.
 *
 * OBOWIĄZKOWY, i to jest tu jedyna decyzja: blokada bez powodu jest bezużyteczna dla
 * drugiego administratora, który widzi zamknięte konto i nie ma jak ustalić, czy
 * odblokowanie jest bezpieczne. Ta sama osoba po pół roku jest tym drugim administratorem.
 *
 * 255 znaków, tak jak kolumna users.blocked_reason - powód ma być zdaniem, a nie raportem.
 */
public record BlockUserRequest(
        @NotBlank(message = "Powód blokady jest wymagany")
        @Size(max = 255, message = "Powód blokady może mieć najwyżej 255 znaków")
        String reason) {
}
