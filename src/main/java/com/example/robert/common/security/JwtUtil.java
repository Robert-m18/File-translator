/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.robert.common.security;

import com.example.robert.common.exception.JwtAuthenticationException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.UUID;

/**
 * Utility do obsługi JWT tokenów
 * Obsługuje: generowanie, walidację i ekstrakcję danych z tokenów
 */
@Slf4j
@Component
public class JwtUtil {

    private final String secret;
    private final long expiration;
    private final long refreshExpiration;

    public JwtUtil(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.expiration}") long expiration,
            @Value("${jwt.refresh-expiration}") long refreshExpiration
    ) {
        this.secret = secret;
        this.expiration = expiration;
        this.refreshExpiration = refreshExpiration;
    }

    /**
     * Zamienia String na kryptograficzny klucz
     */
    private SecretKey getKey() {
        return Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret));
    }

    /**
     * Generuje JWT token dla podanego użytkownika
     */
    public String generateToken(String username) {
        log.debug("Generowanie tokenu dostępowego");
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .claim("type", "access")
                .subject(username)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expiration))
                .signWith(getKey())
                .compact();
    }


    public String generateRefreshToken(String username) {
        return Jwts.builder()
                // jti - losowy identyfikator tokenu. Nie jest ozdobnikiem:
                // znacznik iat zapisywany jest w JWT z dokładnością do SEKUNDY, więc bez jti
                // dwa tokeny wydane temu samemu użytkownikowi w tej samej sekundzie miały
                // identyczny payload, a więc i identyczny podpis - czyli były tym samym
                // ciągiem znaków. Rotacja tokenu tuż po zalogowaniu łamała wtedy ograniczenie
                // unikalności token_hash i kończyła się błędem 409.
                .id(UUID.randomUUID().toString())
                .claim("type", "refresh")
                .subject(username)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + refreshExpiration))
                .signWith(getKey())
                .compact();
    }

    /**
     * Wyciąga nazwę użytkownika z tokenu
     */
    public String extractUsername(String token) {
        try {
            return extractClaims(token).getSubject();
        } catch (ExpiredJwtException ex) {
            log.warn("Token JWT wygasł");
            throw new JwtAuthenticationException("Token wygasł", "EXPIRED_TOKEN", ex);
        } catch (JwtException ex) {
            log.warn("Nieprawidłowy token JWT: {}", ex.getMessage());
            throw new JwtAuthenticationException("Nieprawidłowy token", "INVALID_TOKEN", ex);
        }
    }

    /*
     * Nie ma tu osobnej metody isTokenValid(). Sprawdzanie ważności "na boku" byłoby
     * zbędnym drugim wywołaniem parsera: extractUsername() i tak weryfikuje podpis
     * oraz datę wygaśnięcia, bo parseSignedClaims() rzuca ExpiredJwtException samo z siebie.
     * Dwie metody robiące to samo różnymi ścieżkami to gwarancja, że kiedyś się rozjadą.
     */

    /**
     * Pomocnicza - parsuje token i zwraca payload
     */
    private Claims extractClaims(String token) {
        return Jwts.parser()
                .verifyWith(getKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public String extractTokenType(String token) {
        return (String) extractClaims(token).get("type");
    }
}
