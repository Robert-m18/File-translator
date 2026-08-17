/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.filetranslator.auth;

import com.example.filetranslator.common.security.CookieProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Buduje ciasteczka z tokenami JWT.
 *
 * Tokeny celowo nie trafiają do ciała odpowiedzi - siedzą w ciasteczkach httpOnly,
 * których JavaScript nie odczyta, co odcina najprostszą ścieżkę kradzieży tokenu przez XSS.
 */
@Service
@RequiredArgsConstructor
public class CookieService {

    public static final String ACCESS_TOKEN_COOKIE = "accessToken";
    public static final String REFRESH_TOKEN_COOKIE = "refreshToken";

    private static final String ROOT_PATH = "/";

    private final CookieProperties properties;

    public ResponseCookie createAccessTokenCookie(String token) {
        return build(ACCESS_TOKEN_COOKIE, token, ROOT_PATH, properties.accessTokenMaxAge());
    }

    public ResponseCookie createRefreshTokenCookie(String token) {
        return build(REFRESH_TOKEN_COOKIE, token, properties.refreshTokenPath(), properties.refreshTokenMaxAge());
    }

    public ResponseCookie clearAccessTokenCookie() {
        return build(ACCESS_TOKEN_COOKIE, "", ROOT_PATH, Duration.ZERO);
    }

    public ResponseCookie clearRefreshTokenCookie() {
        return build(REFRESH_TOKEN_COOKIE, "", properties.refreshTokenPath(), Duration.ZERO);
    }

    /**
     * Kasowanie ciasteczka to to samo ciasteczko z maxAge=0 - musi mieć identyczną
     * nazwę i ścieżkę, inaczej przeglądarka potraktuje je jako inne i starego nie usunie.
     */
    private ResponseCookie build(String name, String value, String path, Duration maxAge) {
        return ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(properties.secure())
                .sameSite(properties.sameSite())
                .path(path)
                .maxAge(maxAge)
                .build();
    }
}
