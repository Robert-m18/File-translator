/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.filetranslator.translation;

import com.example.filetranslator.translation.model.TargetLanguage;

/**
 * Opis zdarzenia: zlecenie zostało przetłumaczone.
 *
 * Osobny typ zamiast przekazywania encji, ponieważ jest to opis zdarzenia, a nie stanu wiersza.
 * Niesie wyłącznie dane potrzebne odbiorcy powiadomienia - bez treści pliku i bez treści
 * tłumaczenia. Ma to praktyczne znaczenie: dane te trafiają do ładunku skrzynki nadawczej, czyli
 * do kolumny przechowywanej w bazie jawnym tekstem.
 *
 * Taki właśnie kształt pojedzie na topic, jeśli dojdzie publikacja zdarzeń na zewnątrz - dlatego
 * nie ma tu ani encji, ani niczego, co zależy od warstwy trwałości.
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
