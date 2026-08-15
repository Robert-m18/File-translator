/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.robert.admin.exception;

/**
 * Nie ma konta o podanym identyfikatorze.
 *
 * W odróżnieniu od TranslationJobNotFoundException nie ma tu drugiego, ukrytego znaczenia:
 * administrator widzi wszystkie konta, więc 404 znaczy dokładnie "nie ma takiego wiersza"
 * i niczego nie maskuje.
 */
public class AdminUserNotFoundException extends RuntimeException {

    public AdminUserNotFoundException() {
        super("Nie znaleziono użytkownika o podanym identyfikatorze");
    }
}
