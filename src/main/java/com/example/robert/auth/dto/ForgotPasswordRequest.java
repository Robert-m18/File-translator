/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.robert.auth.dto;

import com.example.robert.common.validation.EmailNormalizer;
import com.example.robert.common.validation.ValidEmail;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ForgotPasswordRequest(

        @ValidEmail(message = "Niepoprawny format email")
        @NotBlank(message = "Email nie może być pusty")
        @Size(max = 255, message = "Email może mieć maksymalnie {max} znaków")
        String email

) {
    /** Postać kanoniczna adresu już na granicy aplikacji - patrz EmailNormalizer. */
    public ForgotPasswordRequest {
        email = EmailNormalizer.normalize(email);
    }
}
