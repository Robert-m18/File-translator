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
     * Liczenie klucza przy każdym użyciu oznaczałoby dekodowanie sekretu i budowanie nowego
     * obiektu klucza na każdym uwierzytelnionym żądaniu, za każdym razem z identycznym wynikiem -
     * sekret wstrzykiwany jest raz i nie zmienia się przez całe życie komponentu.
     *
     * Obiekt klucza jest niezmienny, a biblioteka podpisująca tworzy własny obiekt szyfrujący na
     * każdą operację, więc współdzielenie jednej instancji między wątkami jest bezpieczne.
     *
     * Ubocznym skutkiem, który jest tu zaletą, jest fail-fast: sekret w złym formacie albo
     * krótszy, niż wymaga algorytm podpisu, przerywa start aplikacji zamiast ujawniać się przy
     * pierwszym logowaniu jako błąd serwera, czyli jako błąd konfiguracji przebrany za awarię.
     * Obowiązuje tu ta sama zasada co przy sprawdzaniu konfiguracji magazynu plików i dostawcy
     * tłumaczenia: brakująca konfiguracja ma zatrzymać wdrożenie natychmiast.
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
                // Losowy identyfikator tokenu jest konieczny, ponieważ znacznik wystawienia
                // zapisywany jest z dokładnością do sekundy. Bez niego dwa tokeny wydane temu
                // samemu użytkownikowi w tej samej sekundzie miałyby identyczną treść i identyczny
                // podpis, czyli byłyby tym samym ciągiem znaków - a wtedy obrót sesji tuż po
                // zalogowaniu naruszałby unikalność skrótu tokenu w bazie.
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
