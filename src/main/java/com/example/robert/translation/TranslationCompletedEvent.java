/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.robert.translation;

import com.example.robert.translation.model.TargetLanguage;

/**
 * Fakt: zlecenie zostało przetłumaczone.
 *
 * Osobny typ zamiast przekazywania encji, bo to jest opis ZDARZENIA, a nie stanu wiersza.
 * Niesie wyłącznie to, czego potrzebuje odbiorca powiadomienia - bez treści pliku i bez
 * treści tłumaczenia. Ma to znaczenie praktyczne: te dane trafiają do payloadu skrzynki
 * nadawczej, czyli do kolumny trzymanej w bazie plaintekstem.
 *
 * Gdy dojdzie publikacja do Kafki (patrz TranslationEvents), to jest kształt, który
 * pojedzie na topic - dlatego nie ma tu ani encji, ani niczego, co zależy od Hibernate'a.
 */
public record TranslationCompletedEvent(
        Long jobId,
        Long userId,
        String recipientEmail,
        String recipientName,
        String originalFilename,
        TargetLanguage targetLang,
        int charCount
) {
}
