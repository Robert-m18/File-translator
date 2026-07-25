/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.robert.common.security;

import com.example.robert.common.web.ApiProblem;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Odpowiedź na żądanie do chronionego zasobu BEZ uwierzytelnienia.
 *
 * Bez tego beana Spring Security użyłby domyślnego zachowania i zwrócił pustą
 * odpowiedź 403 - inny kod i inny format niż wszystkie pozostałe błędy w API.
 * Tutaj wymuszamy 401 (brak uwierzytelnienia) i ten sam ProblemDetail co reszta.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ProblemResponseWriter problemWriter;

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {

        log.debug("Nieuwierzytelnione żądanie: {} {}", request.getMethod(), request.getRequestURI());

        problemWriter.write(response, ApiProblem.of(
                HttpStatus.UNAUTHORIZED,
                "Brak uwierzytelnienia",
                "Wymagane zalogowanie",
                "UNAUTHENTICATED"
        ));
    }
}
