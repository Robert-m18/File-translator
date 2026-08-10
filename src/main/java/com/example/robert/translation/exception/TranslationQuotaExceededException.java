/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.robert.translation.exception;

import lombok.Getter;

/**
 * Użytkownik wyczerpał dobowy limit znaków.
 *
 * Limit chroni konto u dostawcy, a nie serwer: darmowy próg liczy się dla CAŁEGO konta,
 * więc jeden użytkownik w pętli wyczerpuje go wszystkim pozostałym. Limiter żądań tego nie
 * załatwia - ogranicza liczbę żądań, a nie liczbę znaków, więc kilka dużych plików mieści
 * się w nim bez problemu.
 *
 * Komunikat mówi wprost, ile zostało: bez tego użytkownik widzi tylko "odmowa" i nie ma jak
 * ustalić, czy chodzi o jego plik, czy o awarię.
 */
@Getter
public class TranslationQuotaExceededException extends RuntimeException {

    private final long remainingChars;

    public TranslationQuotaExceededException(long remainingChars, int dailyLimit) {
        super("Przekroczono dobowy limit " + dailyLimit + " znaków. Pozostało: " + remainingChars + ".");
        this.remainingChars = remainingChars;
    }
}
