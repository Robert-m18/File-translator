/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.robert.translation;

import com.example.robert.common.time.DbClock;
import com.example.robert.translation.repository.TranslationJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Nocne usuwanie starych zleceń tłumaczenia.
 *
 * DLACZEGO TO ISTNIEJE - nie chodzi o rozmiar tabeli, tylko o to, CO w niej leży.
 * source_content i result_content trzymają PLIKI UŻYTKOWNIKÓW: umowy, listy, notatki.
 * Bez retencji wyciek bazy oddaje wszystko, co ktokolwiek kiedykolwiek przetłumaczył,
 * od pierwszego dnia działania usługi. To ta sama zasada, dla której OutboxCleanupJob
 * kasuje wysłane maile - z tą różnicą, że tam chodziło o tokeny, a tu o treść.
 *
 * Użytkownik może skasować swoje zlecenie sam (DELETE /translations/{id}); retencja jest
 * tym, co dzieje się, gdy tego nie zrobi.
 *
 * KASUJEMY WSZYSTKIE STATUSY, w odróżnieniu od skrzynki nadawczej, gdzie FAILED musi
 * przetrwać, bo countFailed() jest jedynym sygnałem "czy maile w ogóle wychodzą". Tutaj taki
 * powód nie zachodzi: nieudane zlecenie sprzed 30 dni nie odpowiada na żadne pytanie, którego
 * nie da się zadać metrykom, a niesie dokładnie tę samą treść pliku co udane.
 *
 * ODLICZAMY OD created_at, a nie od completed_at: zlecenie, które nigdy się nie zakończyło
 * (bo utknęło albo dostawca był niedostępny przez dobę), ma completed_at puste i nigdy nie
 * zostałoby usunięte - czyli akurat najbardziej zapomniane wiersze zostawałyby na zawsze.
 *
 * Osobna klasa, a nie krok w ExpiredTokenCleanupJob: tamten job należy do pakietu auth,
 * a sięganie z niego do repozytorium innej funkcji sklejałoby pakiety przez ich wnętrze.
 * Każda funkcja odpowiada za retencję własnych danych.
 *
 * UWAGA przy wdrożeniu wieloinstancyjnym: @Scheduled odpala się w każdej instancji osobno,
 * więc przy dwóch podach kasowanie wykona się dwa razy. Tu nieszkodliwie (usuwanie jest
 * idempotentne), ale przy zadaniach ze skutkami ubocznymi trzeba dołożyć ShedLock.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TranslationCleanupJob {

    private final TranslationJobRepository repository;
    private final TranslationProperties properties;

    /**
     * 3:20 - po sprzątaniu tokenów (3:00) i po sprzątaniu skrzynki nadawczej (3:10).
     * Rozsunięte w czasie, żeby trzy kasowania nie biły w bazę jednocześnie.
     */
    @Scheduled(cron = "0 20 3 * * *")
    public void cleanupOldJobs() {
        int removed = removeOlderThanRetention();
        log.info("Sprzątanie zleceń tłumaczenia zakończone - usunięto {} zleceń", removed);
    }

    /**
     * Wydzielone z metody harmonogramu, żeby test mógł wywołać samo kasowanie, bez czekania
     * na cron i bez zależności od tego, czy harmonogram jest w danym profilu włączony.
     *
     * @return ile zleceń usunięto
     */
    @Transactional
    public int removeOlderThanRetention() {
        Instant cutoff = DbClock.now().minus(properties.retention());
        return repository.deleteCreatedBefore(cutoff);
    }
}
