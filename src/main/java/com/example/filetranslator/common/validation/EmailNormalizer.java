/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.filetranslator.common.validation;

import java.util.Locale;

/**
 * Sprowadza adres email do postaci kanonicznej: bez białych znaków na brzegach,
 * małymi literami.
 *
 * DLACZEGO TO W OGÓLE ISTNIEJE
 *
 * PostgreSQL porównuje teksty z rozróżnianiem wielkości liter, MySQL przy domyślnym
 * collation - nie. Dopóki aplikacja stała na MySQL-u, zapytanie o "Robert@Example.com"
 * znajdowało wiersz zapisany jako "robert@example.com" i nikt tego nie zauważał.
 * Na PostgreSQL to samo zapytanie nie znajduje nic, a skutki są dwa i oba poważne:
 *
 * 1. Użytkownik, który zarejestrował się wielkimi literami i wraca wpisując małe,
 *    dostaje BAD_CREDENTIALS - nie do odróżnienia od złego hasła, bo API celowo
 *    nie rozróżnia tych przypadków. Sam z tego nie wyjdzie.
 * 2. Unikat uk_users_email przestaje zapobiegać dwóm kontom na ten sam adres pisany
 *    różną wielkością liter. To już nie niewygoda, tylko dziura w modelu tożsamości.
 *
 * Normalizacja siedzi w APLIKACJI, nie w bazie (citext albo indeks na lower(email)),
 * właśnie po to, żeby zachowanie przestało zależeć od silnika. Ten sam kod na H2,
 * PostgreSQL i czymkolwiek dalej daje ten sam wynik.
 *
 * Stosowana w konstruktorach kompaktowych DTO przyjmujących adres, czyli na samej
 * granicy aplikacji - dzięki temu wszystko poniżej widzi już postać kanoniczną
 * i nie ma miejsca, w którym można zapomnieć znormalizować.
 */
public final class EmailNormalizer {

    private EmailNormalizer() {
    }

    /**
     * Locale.ROOT, a nie domyślne locale maszyny, i to nie jest przesada: w locale
     * tureckim "I".toLowerCase() daje "ı" (bez kropki), więc adres z wielkim I
     * normalizowałby się inaczej na serwerze z turecką lokalizacją niż na pozostałych.
     * Konto stałoby się wtedy niedostępne po zmianie ustawień regionalnych maszyny.
     *
     * @return adres w postaci kanonicznej albo null, jeśli wejście było null
     *         (walidacja @NotBlank i tak odrzuci puste - normalizator nie jest walidatorem)
     */
    public static String normalize(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }
}
