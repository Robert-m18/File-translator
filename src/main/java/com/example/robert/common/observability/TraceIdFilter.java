/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.robert.common.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Nadaje każdemu żądaniu identyfikator korelacji.
 *
 * Po co: gdy użytkownik zgłasza "dostałem błąd 500", jedyne co ma w ręku to odpowiedź HTTP.
 * traceId wraca w nagłówku i w ciele błędu (ProblemDetail), a jednocześnie ląduje w MDC,
 * więc trafia do każdej linii loga wygenerowanej w trakcie tego żądania. Jeden grep i mamy
 * pełną ścieżkę - również przy kilku instancjach aplikacji i wielu równoległych requestach.
 *
 * Jeśli nagłówek przyszedł już z zewnątrz (gateway, frontend), przepuszczamy go dalej -
 * dzięki temu korelacja działa przez całą trasę żądania, a nie tylko wewnątrz tej usługi.
 *
 * W większym systemie tę rolę przejmuje Micrometer Tracing / OpenTelemetry (traceId + spanId,
 * propagacja W3C traceparent). Tutaj wystarczy filtr bez dodatkowych zależności.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String TRACE_ID_KEY = "traceId";
    public static final String TRACE_ID_HEADER = "X-Request-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String traceId = request.getHeader(TRACE_ID_HEADER);
        if (!StringUtils.hasText(traceId)) {
            traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }

        MDC.put(TRACE_ID_KEY, traceId);
        response.setHeader(TRACE_ID_HEADER, traceId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            // Krytyczne: wątki są współdzielone przez pulę Tomcata. Bez sprzątania
            // kolejne żądanie obsłużone na tym samym wątku logowałoby cudze traceId.
            MDC.remove(TRACE_ID_KEY);
        }
    }
}
