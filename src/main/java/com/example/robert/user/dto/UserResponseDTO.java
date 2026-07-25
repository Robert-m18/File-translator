/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.robert.user.dto;


public record UserResponseDTO(
        Long id,
        String name,
        String email
) {}