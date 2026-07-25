/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.robert.common.exception;

/**
 * Wyjątek rzucany gdy token JWT jest nieprawidłowy, wygasł lub uszkodzony
 */
public class JwtAuthenticationException extends RuntimeException {

    private final String tokenError;

    public JwtAuthenticationException(String message, String tokenError) {
        super(message);
        this.tokenError = tokenError;
    }

    public JwtAuthenticationException(String message, String tokenError, Throwable cause) {
        super(message, cause);
        this.tokenError = tokenError;
    }

    public String getTokenError() {
        return tokenError;
    }
}

