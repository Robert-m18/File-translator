/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.filetranslator.translation.dto;

import com.example.filetranslator.translation.model.TargetLanguage;
import com.example.filetranslator.translation.model.TranslationStatus;

import java.time.Instant;

/**
 * Stan zlecenia tłumaczenia - bez treści plików.
 *
 * Budowany wyrażeniem konstruktora w JPQL, a nie z encji, i to jest istota tej klasy:
 * TranslationJob niesie dwie kolumny tekstowe po ~1 MB, więc pobranie encji tylko po to,
 * żeby pokazać nazwę pliku i status, wciągnęłoby całą treść źródła i wyniku. Przy liście
 * dwudziestu zleceń to dziesiątki megabajtów przeczytanych i porzuconych w jednym żądaniu.
 *
 * NIE MA TU last_error i to jest decyzja: komunikat od zewnętrznego API w odpowiedzi HTTP
 * to wyciek informacji o systemie (ta sama zasada, którą stosuje GlobalExceptionHandler).
 * Użytkownikowi wystarczy status FAILED, operator ma kolumnę w bazie i log.
 */
public record TranslationJobResponse(
        Long id,
        String originalFilename,
        /** Wykryty przez dostawcę - NULL, dopóki tłumaczenie się nie powiodło. */
        String sourceLang,
        TargetLanguage targetLang,
        TranslationStatus status,
        int charCount,
        Instant createdAt,
        Instant completedAt
) {
}
