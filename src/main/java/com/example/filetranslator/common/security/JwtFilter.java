/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.filetranslator.common.security;

import com.example.filetranslator.common.security.ProblemResponseWriter;
import com.example.filetranslator.common.exception.JwtAuthenticationException;
import com.example.filetranslator.common.web.ApiProblem;
import com.example.filetranslator.auth.CookieService;
import com.example.filetranslator.user.model.User;
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
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Uwierzytelnia żądanie na podstawie tokenu JWT z ciasteczka i ustawia kontekst bezpieczeństwa.
 *
 * Token czytany jest wyłącznie z ciasteczka httpOnly, nigdy z nagłówka Authorization, dzięki
 * czemu pozostaje niedostępny dla JavaScriptu w przeglądarce.
 *
 * Filtr nie jest oznaczony adnotacją komponentu - powstaje jako bean w konfiguracji
 * bezpieczeństwa. Filtr będący komponentem zostałby dodatkowo zarejestrowany w kontenerze
 * serwletów i działał również poza łańcuchem Spring Security, także na ścieżkach świadomie z niego
 * wyłączonych.
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

        String token = extractTokenFromCookies(request);

        // Brak tokenu nie jest błędem - żądanie leci dalej jako anonimowe. O tym, czy endpoint
        // wymaga zalogowania, decyduje konfiguracja autoryzacji, a odpowiedź 401 wystawia punkt
        // wejścia uwierzytelniania.
        if (token == null) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            // Parsowanie tokenu rzuca wyjątek przy niepoprawnym podpisie i po terminie ważności.
            String username = jwtUtil.extractUsername(token);

            /*
             * Token musi być typu dostępowego. Sam poprawny podpis nie wystarcza, ponieważ token
             * odświeżający jest podpisany tym samym kluczem i podstawiony w to ciasteczko
             * przechodziłby tędy jako pełnoprawne uwierzytelnienie.
             *
             * Ma to znaczenie praktyczne: token odświeżający żyje siedem dni, a ten filtr nie
             * sprawdza stanu sesji, więc bez tej kontroli wylogowana sesja otwierałaby chronione
             * endpointy przez cały tydzień. Kontrola lustrzana - odsianie tokenu dostępowego
             * podstawionego pod odświeżanie sesji - znajduje się w AuthService.
             */
            if (!"access".equals(jwtUtil.extractTokenType(token))) {
                throw new JwtAuthenticationException("Nieprawidłowy typ tokenu", "INVALID_TOKEN_TYPE");
            }

            /*
             * Uwierzytelnienie podstawiane jest tylko wtedy, gdy żądanie nie zostało jeszcze
             * uwierzytelnione naprawdę. Uwierzytelnienie anonimowe trzeba tu traktować jak jego
             * brak, ponieważ filtr stoi za filtrem anonimowym (pozycja wymuszona przez ochronę
             * CSRF), więc w kontekście znajduje się już token anonimowy i sam warunek pustego
             * kontekstu nie byłby spełniony nigdy. Poprawny token nie otwierałby wtedy niczego:
             * filtr parsowałby go bez błędu, po czym przepuszczał żądanie dalej jako anonimowe.
             */
            Authentication existing = SecurityContextHolder.getContext().getAuthentication();
            boolean alreadyAuthenticated =
                    existing != null && !(existing instanceof AnonymousAuthenticationToken);

            if (username != null && !alreadyAuthenticated) {
                UserDetails userDetails = userDetailsService.loadUserByUsername(username);

                /*
                 * Konto zablokowane przez administratora nie przechodzi dalej, choćby token był
                 * bez zarzutu. Dzięki temu blokada działa natychmiast, a nie dopiero po wygaśnięciu
                 * żywego tokenu dostępowego. Kontrola nie kosztuje dodatkowego zapytania, bo wiersz
                 * użytkownika jest już wczytany - odczyt wykonuje się przy każdym uwierzytelnionym
                 * żądaniu.
                 *
                 * Sprawdzana jest wyłącznie blokada administracyjna, nigdy ogólny stan konta:
                 * ogólne sprawdzenie objęłoby także blokadę po nieudanych logowaniach, którą może
                 * wywołać każdy, kto zna cudzy adres i kilka razy poda złe hasło. Byłoby to
                 * narzędzie do wyrzucania dowolnego zalogowanego użytkownika z aplikacji.
                 */
                if (userDetails instanceof User user && user.isBlocked()) {
                    throw new JwtAuthenticationException(
                            "Konto zostało zablokowane przez administratora", "ACCOUNT_BLOCKED");
                }

                // Bez adresu w treści - to dana osobowa, a ten wpis powstaje przy każdym żądaniu
                // zalogowanego użytkownika. Powiązanie z konkretną osobą daje identyfikator żądania.
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
        } catch (UsernameNotFoundException ex) {
            /*
             * Token jest poprawny, ale konta już nie ma - zostało skasowane w trakcie pracy
             * użytkownika. Bez tej gałęzi przypadek wpadał do obsługi ogólnej i dawał kod błędu
             * przetwarzania tokenu, na którym frontend się nie rozgałęzia, więc zamiast wrócić na
             * ekran logowania pokazywałby komunikat o wewnętrznej awarii przy każdym kliknięciu.
             *
             * Kod odpowiedzi jest ten sam co dla żądania bez ciasteczka i jest to zgodne ze stanem
             * faktycznym: token wskazuje kogoś, kogo nie ma. Frontend spróbuje po cichu odświeżyć
             * sesję, odbije się od braku tokenów odświeżających i przejdzie na ekran logowania.
             */
            log.warn("Token wskazuje konto, którego już nie ma");
            rejectRequest(response, "Sesja wygasła. Zaloguj się ponownie.", "UNAUTHENTICATED");
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

    /** Odrzuca żądanie odpowiedzią w formacie ProblemDetail, spójną z resztą błędów API. */
    private void rejectRequest(HttpServletResponse response, String detail, String code) throws IOException {
        // Kontekst czyszczony jawnie: wątek pochodzi z puli i mógłby nieść uwierzytelnienie
        // pozostałe po wcześniejszym żądaniu.
        SecurityContextHolder.clearContext();

        problemWriter.write(response, ApiProblem.of(
                HttpStatus.UNAUTHORIZED, "Błąd uwierzytelnienia", detail, code));
    }
}
