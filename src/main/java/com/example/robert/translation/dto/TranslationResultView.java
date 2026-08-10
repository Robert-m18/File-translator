/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.robert.translation.dto;

import com.example.robert.translation.model.TargetLanguage;
import com.example.robert.translation.model.TranslationStatus;

/**
 * Wynik tłumaczenia razem z tym, co potrzebne do zbudowania nagłówka pobierania.
 *
 * Osobno od TranslationJobResponse, bo to jedyny odczyt, który MA prawo wciągnąć treść -
 * pozostałe świadomie jej nie dotykają. Status jest tu po to, żeby odróżnić "jeszcze nie
 * gotowe" (409) od "nie ma takiego zlecenia" (404) bez drugiego zapytania.
 */
public record TranslationResultView(
        TranslationStatus status,
        String originalFilename,
        TargetLanguage targetLang,
        String resultContent
) {
}
