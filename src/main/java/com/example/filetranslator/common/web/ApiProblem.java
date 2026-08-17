/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.filetranslator.common.web;

import com.example.filetranslator.common.observability.TraceIdFilter;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import java.net.URI;
import java.time.Instant;
import java.util.Locale;

/**
 * Fabryka odpowiedzi błędu w formacie RFC 9457 (Problem Details for HTTP APIs).
 *
 * Dlaczego ProblemDetail zamiast własnego rekordu:
 *  - to standard, który Spring 6+ zna natywnie (obsługuje go też ResponseEntityExceptionHandler),
 *  - klienci i narzędzia (generatory SDK, Swagger UI) rozumieją go bez dokumentacji,
 *  - ma zarezerwowane pola type/title/status/detail/instance i pozwala dokładać własne.
 *
 * Dokładamy trzy własne pola:
 *  - code      - stabilny, maszynowy kod błędu; frontend reaguje na niego zamiast parsować
 *                komunikat po polsku, który może się w każdej chwili zmienić,
 *  - timestamp - kiedy błąd wystąpił,
 *  - traceId   - spina odpowiedź z logami serwera (patrz TraceIdFilter).
 */
public final class ApiProblem {

    public static final String PROPERTY_CODE = "code";
    public static final String PROPERTY_TIMESTAMP = "timestamp";
    public static final String PROPERTY_TRACE_ID = "traceId";

    /** Bazowy URI typów błędów. Docelowo powinien prowadzić do dokumentacji danego kodu. */
    private static final URI PROBLEM_TYPE_BASE = URI.create("https://filetranslator.dev/problems/");

    private ApiProblem() {
    }

    public static ProblemDetail of(HttpStatus status, String title, String detail, String code) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setType(PROBLEM_TYPE_BASE.resolve(code.toLowerCase(Locale.ROOT).replace('_', '-')));
        problem.setProperty(PROPERTY_CODE, code);
        problem.setProperty(PROPERTY_TIMESTAMP, Instant.now());

        String traceId = MDC.get(TraceIdFilter.TRACE_ID_KEY);
        if (traceId != null) {
            problem.setProperty(PROPERTY_TRACE_ID, traceId);
        }
        return problem;
    }
}
