/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.filetranslator.notification;

import com.example.filetranslator.common.time.DbClock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Nocne usuwanie wysłanych wiadomości ze skrzynki nadawczej.
 *
 * Powodem nie jest rozmiar tabeli - indeks utrzymuje zapytanie rezerwujące selektywnym
 * niezależnie od liczby wierszy - tylko ich zawartość. Ładunek wiadomości niesie surowy token
 * potwierdzenia adresu albo resetu hasła, a pole odbiorcy jego adres. Poczekalnia rejestracyjna
 * przechowuje wyłącznie skrót tokenu właśnie po to, żeby odczyt bazy nie dawał użytecznego
 * sekretu, więc bezterminowo trzymany wiersz skrzynki nadawczej znosiłby sens tego zabiegu -
 * ten sam token leżałby obok jawnym tekstem.
 *
 * Zadanie należy do pakietu powiadomień, a nie do zadania sprzątającego w pakiecie
 * uwierzytelniania: sięganie stamtąd po repozytorium tego pakietu wiązałoby oba przez ich
 * wnętrze, a nie przez udostępnione API. Każda funkcja pilnuje retencji własnych danych.
 *
 * Przy wdrożeniu wieloinstancyjnym harmonogram uruchamia się w każdej instancji osobno, co jest
 * tutaj nieszkodliwe: kasowanie jest idempotentne, a warunek na czas wysłania sprawia, że druga
 * instancja nie znajdzie już czego usunąć.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxCleanupJob {

    private final OutboxMessageRepository repository;
    private final OutboxProperties properties;

    /*
     * 3:10, kwadrans po sprzątaniu tokenów. Rozsunięte celowo: obie operacje to DELETE
     * po dużym zakresie wierszy i nie ma powodu, żeby konkurowały o te same zasoby bazy
     * w tej samej sekundzie.
     */
    @Scheduled(cron = "0 10 3 * * *")
    @Transactional
    public void cleanupSentMessages() {
        /*
         * Ta sama flaga, która wyłącza wysyłkę. Przy wyłączonej skrzynce nadawczej nic nie
         * dochodzi do statusu SENT, więc sprzątanie nie miałoby czego robić - a testy, które
         * wyłączają publisher właśnie po to, żeby samodzielnie sterować wierszami, nie chcą
         * zadania kasującego im dane w tle. Metodę wołają wprost.
         */
        if (!properties.enabled()) {
            return;
        }

        int removed = removeSentOlderThanRetention();
        log.info("Sprzątanie skrzynki nadawczej zakończone - usunięto {} wysłanych wiadomości",
                removed);
    }

    /**
     * Kasuje wysłane wiadomości starsze niż app.outbox.retention.
     *
     * Wydzielone z metody harmonogramu, żeby test mógł wywołać samo kasowanie bez zależności
     * od flagi enabled - w profilu testowym jest ona wyłączona.
     *
     * @return ile wierszy usunięto
     */
    @Transactional
    public int removeSentOlderThanRetention() {
        // Obcięcie do precyzji kolumny tą samą drogą co reszta skrzynki nadawczej (DbClock).
        // Przy granicy odliczanej o dobę wstecz zaokrąglenie o pół mikrosekundy nie ma
        // znaczenia, ale jedno źródło czasu dla całej funkcji jest warte więcej niż wyjątek.
        Instant cutoff = DbClock.now().minus(properties.retention());
        return repository.deleteSentBefore(cutoff);
    }
}
