/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.filetranslator.common.security;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Zapisuje ProblemDetail wprost do odpowiedzi HTTP.
 *
 * Potrzebne tam, gdzie @RestControllerAdvice nie sięga - czyli w filtrach i w punktach
 * wejścia Spring Security. Filtry działają PRZED DispatcherServletem, więc rzucony
 * w nich wyjątek nigdy nie trafi do globalnego handlera wyjątków.
 *
 * Serializację robi Jackson, a nie ręczne sklejanie stringa. Poprzednia wersja
 * budowała JSON przez String.format, przez co komunikat zawierający cudzysłów
 * albo backslash produkował niepoprawny JSON.
 *
 * Uwaga na import: Spring Boot 4 przeszedł na Jackson 3 (pakiet tools.jackson).
 * Stary com.fasterxml.jackson.databind.ObjectMapper bywa jeszcze na classpath
 * tranzytywnie (jjwt, springdoc), ale kontener nie ma go jako beana.
 */
@Component
@RequiredArgsConstructor
public class ProblemResponseWriter {

    private final ObjectMapper objectMapper;

    public void write(HttpServletResponse response, ProblemDetail problem) throws IOException {
        response.setStatus(problem.getStatus());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(), problem);
    }
}
