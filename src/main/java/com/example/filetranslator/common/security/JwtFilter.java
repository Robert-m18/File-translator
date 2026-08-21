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
             * To nie jest teoretyczne: token odświeżający żyje 7 dni, a ten filtr nie
             * sprawdza stanu SESJI. Unieważnianie sesji działa na rodzinach tokenów
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

                /*
                 * 3a. Konto zablokowane przez administratora nie przechodzi dalej, choćby
                 * token był bez zarzutu. Blokada ma działać NATYCHMIAST, a nie po wygaśnięciu
                 * żywego tokenu dostępowego - inaczej zablokowany pracowałby jeszcze przez
                 * 15 minut. To jedyne miejsce, w którym da się to domknąć, i wolno tu było
                 * dołożyć sprawdzenie tylko dlatego, że wiersz użytkownika i tak jest już
                 * w ręku: loadUserByUsername wyżej czyta go z bazy przy KAŻDYM
                 * uwierzytelnionym żądaniu. Koszt wynosi zero zapytań.
                 *
                 * SPRAWDZAMY WYŁĄCZNIE isBlocked(), NIGDY isAccountNonLocked(). Ta druga
                 * obejmuje także blokadę po nieudanych logowaniach, a tę wywołuje KAŻDY,
                 * kto zna czyjś adres i wpisze kilka razy złe hasło. Ogólne sprawdzenie
                 * zamieniłoby więc automatyczną blokadę w narzędzie do wybijania dowolnego
                 * zalogowanego użytkownika z aplikacji. Kontrola negatywna:
                 * AdminPanelTest.failedLoginLockout_shouldNotKillLiveSession.
                 *
                 * JwtFilterTest tej gałęzi nie pokryje - mockuje loadUserByUsername na
                 * org.springframework.security.core.userdetails.User, więc instanceof naszej
                 * encji jest tam fałszywy i test przechodzi niezależnie od tej linii.
                 * Ta sama sytuacja co CurrentUserTest kontra JwtFilterTest.
                 */
                if (userDetails instanceof User user && user.isBlocked()) {
                    throw new JwtAuthenticationException(
                            "Konto zostało zablokowane przez administratora", "ACCOUNT_BLOCKED");
                }

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
        } catch (UsernameNotFoundException ex) {
            /*
             * Token poprawny, ale konta już nie ma - administrator skasował je w trakcie
             * pracy użytkownika. Bez tej gałęzi wpadało to do generycznego catch niżej,
             * czyli do 401 TOKEN_PROCESSING_ERROR: kodu, na którym front się NIE rozgałęzia,
             * więc zamiast wrócić na ekran logowania pokazywał "Błąd przy przetwarzaniu
             * tokena" przy każdym kliknięciu - zdanie o wewnętrznej awarii w sytuacji,
             * w której nic się nie zepsuło.
             *
             * UNAUTHENTICATED, czyli ten sam kod co żądanie bez ciasteczka, jest tu prawdą:
             * token wskazuje kogoś, kogo nie ma. Front spróbuje po cichu odświeżyć sesję
             * (tokeny odświeżające zniknęły z kaskadą, więc odbije się o 401) i przejdzie
             * na ekran logowania - dokładnie to, co ma się stać.
             *
             * Rejestrujemy na poziomie WARN bez adresu: id nie ma już skąd wziąć, a traceId
             * wystarczy do skorelowania z logiem usunięcia konta.
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

    private void rejectRequest(HttpServletResponse response, String detail, String code) throws IOException {
        // Kontekst czyszczony jawnie: wątek pochodzi z puli i mógłby nieść
        // uwierzytelnienie pozostałe po wcześniejszym żądaniu.
        SecurityContextHolder.clearContext();

        problemWriter.write(response, ApiProblem.of(
                HttpStatus.UNAUTHORIZED, "Błąd uwierzytelnienia", detail, code));
    }
}
