/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.robert.translation.exception;

import com.example.robert.translation.model.TranslationStatus;
import lombok.Getter;

/**
 * Próba pobrania wyniku zlecenia, które nie jest jeszcze gotowe (albo się nie powiodło).
 *
 * Odpowiedź niesie aktualny status, bo to jedyna informacja, która pozwala frontendowi
 * zdecydować, czy poczekać i spróbować ponownie (PENDING/PROCESSING), czy przestać
 * odpytywać (FAILED). Bez tego klient odpytywałby martwe zlecenie w nieskończoność.
 */
@Getter
public class TranslationNotReadyException extends RuntimeException {

    private final TranslationStatus status;

    public TranslationNotReadyException(TranslationStatus status) {
        super("Tłumaczenie nie jest gotowe do pobrania (status: " + status + ")");
        this.status = status;
    }
}
