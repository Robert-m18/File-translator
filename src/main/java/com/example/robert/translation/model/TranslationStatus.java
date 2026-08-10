/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.robert.translation.model;

/**
 * Stan zlecenia tłumaczenia. Wartość widoczna dla klienta API - front rozgałęzia się
 * na niej, więc nazwy są częścią kontraktu.
 */
public enum TranslationStatus {

    /** Czeka w kolejce, żaden worker jeszcze go nie wziął. */
    PENDING,

    /**
     * Worker zarezerwował zlecenie i rozmawia z dostawcą.
     *
     * Skrzynka nadawcza maili świadomie NIE ma odpowiednika tego stanu, bo osierocony
     * status "w trakcie" strandowałby wiersz na zawsze. Tutaj jest, bo użytkownik patrzy
     * na ekran i różnica między "w kolejce" a "tłumaczę" jest dla niego informacją -
     * a przed strandowaniem chroni to samo co tam: okno rezerwacji na next_attempt_at.
     * Zlecenie porzucone przez martwy proces wraca do obiegu po upływie tego okna,
     * niezależnie od tego, jaki status ma wpisany.
     */
    PROCESSING,

    /** Przetłumaczone, wynik gotowy do pobrania. */
    DONE,

    /** Poddaliśmy się - błąd trwały albo wyczerpana liczba podejść. Powód w last_error. */
    FAILED
}
