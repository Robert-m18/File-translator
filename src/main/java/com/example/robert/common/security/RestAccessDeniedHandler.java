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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Odpowiedź na żądanie użytkownika ZALOGOWANEGO, ale bez wymaganych uprawnień
 * (np. zwykły USER wchodzi na /users/**, gdzie wymagany jest ROLE_ADMIN).
 *
 * Rozróżnienie 401 vs 403 nie jest kosmetyką: frontend na 401 przekierowuje na login
 * albo próbuje odświeżyć token, a na 403 pokazuje "brak dostępu". Zwracanie jednego
 * kodu w obu sytuacjach powoduje pętle przekierowań na stronę logowania.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    private final ProblemResponseWriter problemWriter;

    @Override
    public void handle(HttpServletRequest request,
                       HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {

        log.warn("Odmowa dostępu: {} {}", request.getMethod(), request.getRequestURI());

        problemWriter.write(response, ApiProblem.of(
                HttpStatus.FORBIDDEN,
                "Brak uprawnień",
                "Nie masz uprawnień do tego zasobu",
                "ACCESS_DENIED"
        ));
    }
}
