/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.robert.notification;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Nocne usuwanie wysłanych wiadomości ze skrzynki nadawczej.
 *
 * DLACZEGO TO ISTNIEJE - do tej pory NIC nie kasowało wierszy z outbox_messages, więc każdy
 * wysłany mail zostawał w bazie na zawsze. Problemem nie był rozmiar tabeli (indeks
 * na (status, next_retry_at) trzyma zapytanie rezerwujące selektywnym), tylko zawartość:
 * payload niesie SUROWY token weryfikacyjny albo resetu hasła, recipient adres email.
 * pending_registrations trzyma wyłącznie SHA-256 tokenu, żeby odczyt bazy nie dawał
 * użytecznego sekretu - bezterminowo trzymany wiersz outboxu znosił sens tego zabiegu,
 * bo ten sam token leżał obok w plaintekście.
 *
 * DLACZEGO OSOBNA KLASA, A NIE KROK W ExpiredTokenCleanupJob - tamten job należy do pakietu
 * auth i sprząta tabele auth. Sięganie z niego po repozytorium notification wiązałoby oba
 * pakiety przez wewnętrzne repozytorium, a nie przez API (MailOutbox). Każda funkcja pilnuje
 * retencji własnych danych.
 *
 * UWAGA na wdrożenie wieloinstancyjne: @Scheduled odpala się w każdej instancji osobno.
 * Tutaj jest to nieszkodliwe - kasowanie jest idempotentne, a warunek na sentAt sprawia,
 * że druga instancja po prostu nie znajdzie już czego usunąć. Ta sama uwaga co
 * w ExpiredTokenCleanupJob.
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
        // Obcięcie do precyzji kolumny tą samą drogą co reszta skrzynki nadawczej (OutboxClock).
        // Przy granicy odliczanej o dobę wstecz zaokrąglenie o pół mikrosekundy nie ma
        // znaczenia, ale jedno źródło czasu dla całej funkcji jest warte więcej niż wyjątek.
        LocalDateTime cutoff = OutboxClock.now().minus(properties.retention());
        return repository.deleteSentBefore(cutoff);
    }
}
