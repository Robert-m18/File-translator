/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.robert.auth.dto;

import com.example.robert.common.validation.ValidPassword;
import jakarta.validation.constraints.NotBlank;

public record ResetPasswordRequest(

        @NotBlank(message = "Token nie może być pusty")
        String token,

        /* Ta sama polityka co przy rejestracji - patrz ValidPassword. */
        @ValidPassword
        String password

) {}
