/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.filetranslator.common.security;

import com.example.filetranslator.common.exception.JwtAuthenticationException;
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

    /**
     * Klucz liczony RAZ, w konstruktorze, a nie przy każdym podpisie i każdym parsowaniu.
     *
     * Dawniej robiła to prywatna metoda getKey(), wołana z trzech miejsc - w tym z
     * extractClaims(), czyli na KAŻDYM uwierzytelnionym żądaniu przez JwtFilter. Każde
     * wywołanie dekodowało base64 i budowało nowy SecretKeySpec, żeby otrzymać obiekt
     * identyczny z poprzednim: sekret jest wstrzykiwany raz i nie zmienia się przez całe
     * życie beana. Koszt był mikrosekundowy, więc to nie jest optymalizacja - to usunięcie
     * pracy, która nigdy nie miała powodu się powtarzać.
     *
     * SecretKeySpec jest niezmienny, a jjwt tworzy własny Mac na każdą operację, więc
     * współdzielenie jednej instancji między wątkami jest bezpieczne.
     *
     * SKUTEK UBOCZNY, KTÓRY JEST TU ZALETĄ: zły JWT_SECRET (nie-base64 albo krótszy niż
     * 256 bitów wymagane przez HS256) wywraca teraz START aplikacji, a nie pierwsze
     * logowanie. Wcześniej kontekst wstawał zdrowo, a wyjątek z Keys.hmacShaKeyFor wychodził
     * dopiero z ścieżki żądania jako 500 - czyli błąd konfiguracji przebrany za awarię
     * aplikacji. To ta sama zasada, co w S3ClientConfig.requireComplete i w konstruktorze
     * DeepLTranslationProvider: brakująca konfiguracja ma zatrzymać wdrożenie od razu.
     */
    private final SecretKey key;
    private final long expiration;
    private final long refreshExpiration;

    public JwtUtil(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.expiration}") long expiration,
            @Value("${jwt.refresh-expiration}") long refreshExpiration
    ) {
        this.key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret));
        this.expiration = expiration;
        this.refreshExpiration = refreshExpiration;
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
                .signWith(key)
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
                .signWith(key)
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
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public String extractTokenType(String token) {
        return (String) extractClaims(token).get("type");
    }
}
