/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.robert.translation.dto;

import com.example.robert.translation.model.TargetLanguage;
import com.example.robert.translation.model.TranslationStatus;

/**
 * Wskazanie na wynik razem z tym, co potrzebne do zbudowania nagłówka pobierania.
 *
 * Niesie KLUCZ, nie treść: od changesetu 0011 plik leży w magazynie obiektowym, a bajty
 * pobiera się dopiero przy strumieniowaniu odpowiedzi. Dzięki temu żaden odczyt z bazy
 * nie wciąga pliku na stertę - także ten.
 *
 * Status jest tu po to, żeby odróżnić "jeszcze nie gotowe" (409) od "nie ma takiego
 * zlecenia" (404) bez drugiego zapytania.
 */
public record TranslationResultView(
        TranslationStatus status,
        String originalFilename,
        TargetLanguage targetLang,
        String resultObjectKey
) {
}
