/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.filetranslator.auth.oauth2;

/**
 * Kody odmowy na ścieżce logowania przez Google.
 *
 * Jadą do frontendu w parametrze ?error= adresu powrotnego, a nie w ciele ProblemDetail -
 * to jedyne miejsce w tej aplikacji, gdzie błąd trafia do przeglądarki W TRAKCIE NAWIGACJI,
 * a nie do wywołania fetch. Reguła "każda odpowiedź błędu to ProblemDetail z kodem" dotyczy
 * API wołanego z JavaScriptu; tutaj użytkownik zobaczyłby surowy JSON zamiast strony.
 * Sam KOD zostaje - front rozgałęzia się po nim tak samo, jak po polu code z /auth/me.
 *
 * ACCOUNT_BLOCKED jest celowo tym samym łańcuchem, którego używa logowanie hasłem
 * i JwtFilter: dla frontendu to ten sam stan i ta sama reakcja, niezależnie od tego,
 * którą drogą użytkownik próbował wejść.
 */
final class GoogleAuthError {

    /**
     * Google nie potwierdziło adresu email.
     *
     * Bez tej kontroli ktoś, kto założy konto Google na cudzy adres, przejmuje konto
     * w tej aplikacji - potwierdzony adres jest CAŁYM dowodem tożsamości przy łączeniu
     * kont w UserService.findOrCreateGoogleUser.
     */
    static final String EMAIL_NOT_VERIFIED = "GOOGLE_EMAIL_NOT_VERIFIED";

    /** Konto zablokowane przez administratora. Ten sam kod co na ścieżce hasłowej. */
    static final String ACCOUNT_BLOCKED = "ACCOUNT_BLOCKED";

    /** Wszystko pozostałe: odmowa zgody, zły kod, awaria po stronie Google. */
    static final String AUTH_FAILED = "GOOGLE_AUTH_FAILED";

    private GoogleAuthError() {
    }
}
