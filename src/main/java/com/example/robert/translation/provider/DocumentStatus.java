/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.robert.translation.provider;

/**
 * Stan tłumaczenia dokumentu u dostawcy.
 *
 * Cztery stany zamiast surowego łańcucha z API: worker rozgałęzia się po nich i nie zna
 * ani nazw, których używa DeepL, ani tego, że w ogóle chodzi o DeepL.
 *
 * billedCharacters jest NULL do chwili zakończenia i to jest tu istotna informacja, a nie
 * brak danych: dla dokumentu liczby znaków nie da się poznać przed przetłumaczeniem, więc
 * dobowy limit egzekwuje się wyłącznie wstecz (patrz UploadedFile.charCount).
 */
public record DocumentStatus(State state, Integer billedCharacters, String errorMessage) {

    public enum State {
        /** Przyjęty, czeka w kolejce dostawcy. */
        QUEUED,
        /** Tłumaczony w tej chwili. */
        TRANSLATING,
        /** Gotowy do pobrania. */
        DONE,
        /** Dostawca poddał się - błąd nieodwracalny po jego stronie. */
        ERROR
    }

    public boolean inProgress() {
        return state == State.QUEUED || state == State.TRANSLATING;
    }
}
