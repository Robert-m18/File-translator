/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.robert.auth.dto;

/**
 * Token CSRF wydawany frontendowi.
 *
 * Token wraca w CIELE odpowiedzi, a nie tylko w ciasteczku, i to jest tu istotne.
 * Przy wdrożeniu, w którym frontend stoi pod inną domeną niż API, ciasteczko API jest
 * dla skryptów frontendu obce - JavaScript nie ma jak go odczytać, więc klasyczny wariant
 * "double submit" z ciasteczkiem czytanym przez JS w ogóle by nie działał.
 *
 * Dlatego ciasteczko XSRF-TOKEN zostaje httpOnly (przeglądarka dosyła je sama),
 * a frontend bierze wartość stąd, trzyma w pamięci i odsyła w nagłówku podanym
 * w headerName. Serwer porównuje jedno z drugim.
 *
 * @param headerName nagłówek, w którym należy odsyłać token (domyślnie X-XSRF-TOKEN)
 * @param token      wartość do odesłania
 */
public record CsrfTokenResponse(String headerName, String token) {}
