/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.filetranslator.auth.oauth2;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;

/**
 * Odsyła nieudane logowanie Google na frontend, z kodem błędu w parametrze.
 *
 * DLACZEGO NIE ProblemDetail, mimo reguły "każda odpowiedź błędu to ProblemDetail z kodem":
 * ta reguła dotyczy API wołanego z JavaScriptu. Tutaj przeglądarka jest W TRAKCIE NAWIGACJI
 * - wróciła właśnie z accounts.google.com pod adres powrotny - więc odpowiedź trafia prosto
 * na ekran. Użytkownik zobaczyłby surowy JSON zamiast strony logowania. Sam KOD nie ginie:
 * jedzie w parametrze error=, a front rozgałęzia się po nim tak samo, jak po polu code
 * z /auth/me.
 *
 * Kod bierze się z OAuth2Error, gdzie wstawia go GoogleOidcUserService. Wszystko, czego
 * ta klasa nie rozpoznaje - odmowa zgody na ekranie Google, zużyty kod autoryzacyjny,
 * brakujące ciasteczko żądania autoryzacyjnego, awaria po stronie dostawcy - dostaje jeden
 * kod zbiorczy. Rozróżnianie ich w adresie URL nic by frontendowi nie dało (reakcja jest
 * zawsze ta sama: pokaż ekran logowania), a wystawiłoby użytkownikowi szczegóły
 * wewnętrznego przebiegu.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GoogleOAuth2FailureHandler implements AuthenticationFailureHandler {

    private final GoogleOAuth2Properties properties;

    @Value("${app.frontend.url}")
    private String frontendUrl;

    @Override
    public void onAuthenticationFailure(HttpServletRequest request,
                                        HttpServletResponse response,
                                        AuthenticationException exception) throws IOException {

        String code = exception instanceof OAuth2AuthenticationException oauth2Exception
                ? oauth2Exception.getError().getErrorCode()
                : GoogleAuthError.AUTH_FAILED;

        // Bez adresu email i bez treści tokenu w logu - to jest ścieżka logowania.
        // Sam komunikat wyjątku bywa od dostawcy i może nieść parametry żądania,
        // dlatego trafia tu wyłącznie kod.
        log.warn("Nieudane logowanie przez Google, kod={}", code);

        String target = UriComponentsBuilder.fromUriString(frontendUrl + properties.failurePath())
                .queryParam("error", code)
                .build()
                .encode()
                .toUriString();

        response.sendRedirect(target);
    }
}
