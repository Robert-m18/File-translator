/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.robert.common.security;

import com.example.robert.common.security.ProblemResponseWriter;
import com.example.robert.common.exception.JwtAuthenticationException;
import com.example.robert.common.web.ApiProblem;
import com.example.robert.auth.CookieService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Waliduje JWT z ciasteczka "accessToken" i ustawia kontekst bezpieczeństwa.
 *
 * Uwaga na rejestrację tego filtra: NIE jest oznaczony @Component, tylko tworzony jako
 * @Bean w SecurityConfig. Filtr będący @Component Spring Boot rejestruje dodatkowo
 * w kontenerze serwletów, przez co działałby również poza łańcuchem Spring Security -
 * także na ścieżkach, których świadomie z niego wyłączyliśmy.
 */
@Slf4j
@RequiredArgsConstructor
public class JwtFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final UserDetailsService userDetailsService;
    private final ProblemResponseWriter problemWriter;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        // 1. Szukamy tokena w ciasteczku
        String token = extractTokenFromCookies(request);

        // Brak tokena to nie błąd - request leci dalej jako anonimowy.
        // O tym, czy dany endpoint wymaga zalogowania, decyduje konfiguracja autoryzacji,
        // a odpowiedź 401 wystawi RestAuthenticationEntryPoint.
        if (token == null) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            // 2. Parsujemy token - rzuca wyjątek, jeśli nieważny albo wygasły
            String username = jwtUtil.extractUsername(token);

            /*
             * 2a. Token musi być typu "access". Sam poprawny podpis nie wystarcza, bo token
             * ODŚWIEŻAJĄCY jest podpisany tym samym kluczem - podstawiony w ciasteczko
             * accessToken przechodziłby tędy jako pełnoprawne uwierzytelnienie.
             *
             * To nie jest teoretyczne: token odświeżający żyje 7 dni, a ten filtr w ogóle
             * nie zagląda do bazy. Unieważnianie sesji działa na rodzinach tokenów
             * odświeżających sprawdzanych w AuthService.refreshToken, więc bez tego
             * sprawdzenia WYLOGOWANA sesja otwierałaby chronione endpointy jeszcze przez
             * tydzień. AuthService robi kontrolę lustrzaną w drugą stronę (odsiewa access
             * token podstawiony pod /auth/refresh) - tutaj brakowało jej od zawsze.
             */
            if (!"access".equals(jwtUtil.extractTokenType(token))) {
                throw new JwtAuthenticationException("Nieprawidłowy typ tokenu", "INVALID_TOKEN_TYPE");
            }

            /*
             * 3. Podstawiamy użytkownika, o ile nikt wcześniej nie uwierzytelnił żądania
             * naprawdę. Uwierzytelnienie ANONIMOWE trzeba tu wyraźnie potraktować jak jego
             * brak, bo ten filtr stoi ZA AnonymousAuthenticationFilter (patrz uzasadnienie
             * pozycji w SecurityConfig) - w kontekście siedzi więc już AnonymousAuthenticationToken
             * i sam warunek "getAuthentication() == null" nie jest spełniony NIGDY.
             *
             * Skutek był taki, że poprawny token nie otwierał niczego: filtr parsował go bez
             * błędu, po czym przepuszczał żądanie dalej jako anonimowe, a AuthorizationFilter
             * odsyłał 401 z kodem UNAUTHENTICATED. Objaw mylący - wygląda jak zły token,
             * a token jest w porządku.
             *
             * Testy jednostkowe tego nie łapią, bo wołają filtr poza łańcuchem, gdzie kontekst
             * faktycznie jest pusty. Pilnuje tego CurrentUserTest, idący przez cały łańcuch.
             */
            Authentication existing = SecurityContextHolder.getContext().getAuthentication();
            boolean alreadyAuthenticated =
                    existing != null && !(existing instanceof AnonymousAuthenticationToken);

            if (username != null && !alreadyAuthenticated) {
                UserDetails userDetails = userDetailsService.loadUserByUsername(username);

                // Bez adresu email w treści - to dana osobowa, a ten log wykonuje się teraz
                // przy KAŻDYM żądaniu zalogowanego użytkownika (wcześniej ścieżka sukcesu tego
                // filtra nie wykonywała się w ogóle, więc naruszenie było uśpione).
                // Powiązanie z konkretną osobą daje traceId, wspólny dla całego żądania.
                log.debug("Token JWT zweryfikowany, uwierzytelnienie ustawione");

                UsernamePasswordAuthenticationToken authToken =
                        new UsernamePasswordAuthenticationToken(
                                userDetails,
                                null,
                                userDetails.getAuthorities()
                        );

                SecurityContextHolder.getContext().setAuthentication(authToken);
            }

            filterChain.doFilter(request, response);

        } catch (JwtAuthenticationException ex) {
            log.warn("Błąd JWT: {}", ex.getMessage());
            rejectRequest(response, ex.getMessage(), ex.getTokenError());
        } catch (Exception ex) {
            log.error("Błąd w filtrze JWT", ex);
            rejectRequest(response, "Błąd przy przetwarzaniu tokena", "TOKEN_PROCESSING_ERROR");
        }
    }

    private String extractTokenFromCookies(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return null;
        }

        for (Cookie cookie : request.getCookies()) {
            if (CookieService.ACCESS_TOKEN_COOKIE.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private void rejectRequest(HttpServletResponse response, String detail, String code) throws IOException {
        // Kontekst czyszczony jawnie: wątek pochodzi z puli i mógłby nieść
        // uwierzytelnienie pozostałe po wcześniejszym żądaniu.
        SecurityContextHolder.clearContext();

        problemWriter.write(response, ApiProblem.of(
                HttpStatus.UNAUTHORIZED, "Błąd uwierzytelnienia", detail, code));
    }
}
