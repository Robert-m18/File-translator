/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.filetranslator.auth.oauth2;

import com.example.filetranslator.auth.CookieService;
import com.example.filetranslator.auth.RefreshTokenService;
import com.example.filetranslator.common.security.JwtUtil;
import com.example.filetranslator.user.UserService;
import com.example.filetranslator.user.model.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Zamienia świeże uwierzytelnienie OAuth2 na WŁASNE ciasteczka z tokenami i odsyła
 * przeglądarkę na frontend.
 *
 * KLUCZOWA WŁASNOŚĆ CAŁEGO ROZWIĄZANIA: OidcUser NIE zostaje zasadą uwierzytelnienia
 * aplikacji. Żyje wyłącznie przez czas obsługi powrotu z Google i tutaj zamienia się na tę
 * samą parę tokenów, którą wystawia POST /auth/login. Dzięki temu JwtFilter,
 * UserDetailsServiceImpl, GET /auth/me i POST /auth/refresh nie zmieniają się ani o linijkę,
 * a rotacja tokenów, wykrywanie ponownego użycia i wylogowanie działają dla konta Google
 * bez jednego wiersza kodu napisanego osobno.
 *
 * AuthService.login NIE jest tu wołane i nie może być - nie ma hasła do porównania.
 * Powtórzenie trzech linijek wystawiających ciasteczka jest tańsze niż parametr
 * "a teraz bez hasła" w metodzie logującej, który trzeba by potem omijać wzrokiem
 * przy każdym czytaniu ścieżki hasłowej.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GoogleOAuth2SuccessHandler implements AuthenticationSuccessHandler {

    private final UserService userService;
    private final JwtUtil jwtUtil;
    private final RefreshTokenService refreshTokenService;
    private final CookieService cookieService;
    private final GoogleOAuth2Properties properties;

    @Value("${app.frontend.url}")
    private String frontendUrl;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request,
                                        HttpServletResponse response,
                                        Authentication authentication) throws IOException {

        OidcUser oidcUser = (OidcUser) authentication.getPrincipal();

        /*
         * Konto odnajdywane jest po identyfikatorze konta Google, a nie po adresie z tokenu
         * tożsamości. Konto mogło zostać powiązane wcześniej, a adres u dostawcy
         * zmieniony później - wtedy adres z tokenu nie jest adresem zapisanym w bazie.
         * Token musi nieść adres z bazy, ponieważ to po nim warstwa uwierzytelniania odnajduje
         * użytkownika przy każdym kolejnym żądaniu.
         *
         * Konto na tym etapie na pewno istnieje - założył je albo odnalazł
         * GoogleOidcUserService chwilę wcześniej. Pusty Optional oznaczałby więc błąd
         * programistyczny, a nie sytuację do obsłużenia.
         */
        User user = userService.findEntityByGoogleSub(oidcUser.getSubject())
                .orElseThrow(() -> new IllegalStateException(
                        "Brak konta dla uwierzytelnionego użytkownika Google"));

        String accessToken = jwtUtil.generateToken(user.getEmail());
        String refreshToken = jwtUtil.generateRefreshToken(user.getEmail());
        refreshTokenService.startSession(user.getEmail(), refreshToken);

        response.addHeader(HttpHeaders.SET_COOKIE,
                cookieService.createAccessTokenCookie(accessToken).toString());
        response.addHeader(HttpHeaders.SET_COOKIE,
                cookieService.createRefreshTokenCookie(refreshToken).toString());

        log.info("Wystawiono sesję po logowaniu Google, id={}", user.getId());
        response.sendRedirect(frontendUrl + properties.successPath());
    }
}
