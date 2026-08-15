/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.robert.translation.model;

import com.example.robert.translation.TranslationProperties;
import com.example.robert.user.model.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Jedno zlecenie tłumaczenia pliku.
 *
 * Uzasadnienie modelu, mechaniki rezerwacji i długości kolumn: changelog
 * 0007-translation-jobs.xml.
 *
 * TREŚCI PLIKÓW TU NIE MA - od changesetu 0011 leżą w magazynie obiektowym, a wiersz
 * trzyma same klucze. Odczyty prezentacyjne dalej idą przez projekcje
 * w TranslationJobRepository: powód przestał być rozmiarem wiersza, ale zasada się nie
 * zmienia, bo projekcja jest też tym, co pilnuje, żeby przez API nie wyciekło pole,
 * którego nikt nie zamierzał pokazywać.
 */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "translation_jobs")
public class TranslationJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // LAZY: worker potrzebuje właściciela dopiero przy zamawianiu maila, a większość
    // zapytań i tak filtruje po user_id, nie po obiekcie użytkownika.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "original_filename", nullable = false, length = 255)
    private String originalFilename;

    /** Wykrywany przez dostawcę, więc NULL aż do udanego tłumaczenia. */
    @Column(name = "source_lang", length = 10)
    private String sourceLang;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_lang", nullable = false, length = 10)
    private TargetLanguage targetLang;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TranslationStatus status = TranslationStatus.PENDING;

    /**
     * Klucz pliku źródłowego w magazynie obiektowym. Nie URL - uzasadnienie w ObjectStore
     * i w changesecie 0011.
     *
     * Ustawiany w konstruktorze, bo obiekt jest zapisywany PRZED wstawieniem wiersza:
     * wiersz bez pliku to zlecenie, które nigdy się nie wykona, a osierocony plik to tylko
     * zajęte miejsce, które sprząta reguła wygasania na kubełku.
     */
    @Column(name = "source_object_key", nullable = false, length = 512)
    private String sourceObjectKey;

    /** Klucz wyniku. NULL do chwili udanego tłumaczenia - tak jak wcześniej result_content. */
    @Column(name = "result_object_key", length = 512)
    private String resultObjectKey;

    /**
     * SHA-256 treści źródłowej, szesnastkowo - odcisk pod deduplikację.
     *
     * Nullable wyłącznie ze względu na wiersze sprzed changesetu 0008: skrótu nie da się
     * policzyć w przenośnym SQL-u, więc nie było czym ich wypełnić. Każde nowe zlecenie
     * ma go ustawionego w konstruktorze.
     */
    @Column(name = "content_hash", length = 64)
    private String contentHash;

    /**
     * Dostawca, który wyprodukował wynik - znany dopiero przy zapisie, stąd NULL do tego czasu.
     *
     * Wchodzi do klucza deduplikacji razem z odciskiem treści i językiem docelowym. Bez niego
     * gotowe zlecenie wykonane przez atrapę (ECHO) zaspokoiłoby zlecenie kierowane do DEEPL -
     * jedyny przypadek, w którym cache oddaje wynik BŁĘDNY, a nie tylko szybki.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "provider", length = 20)
    private TranslationProperties.Provider provider;

    /** Liczba znaków źródła - długość pliku pokazywana użytkownikowi, bez czytania treści. */
    @Column(name = "char_count", nullable = false)
    private int charCount;

    /**
     * Znaki FAKTYCZNIE WYDANE u dostawcy - to na niej, a nie na charCount, liczy się dobowy limit.
     *
     * Zlecenie zaspokojone z cache'a ma tu 0, bo u dostawcy nie kosztowało ani znaku. Rozdzielenie
     * jest konieczne, bo po fakcie wiersz z cache'a jest nieodróżnialny od zwykłego: provider
     * dostaje wypełniony tak samo (musi, bo wchodzi do klucza deduplikacji). Uzasadnienie
     * i historia defektu: changelog 0009-translation-billed-chars.xml.
     *
     * Wartość ustawiana przy przyjęciu zlecenia jest PRZEWIDYWANIEM (patrz TranslationService),
     * a worker koryguje ją przy zapisie wyniku - dopiero tam wiadomo, czy dostawca był wołany.
     */
    @Column(name = "billed_chars", nullable = false)
    private int billedChars;

    /** Liczy podejścia (rezerwacje), nie potwierdzone porażki - jak w skrzynce nadawczej. */
    @Column(nullable = false)
    private int attempts = 0;

    /**
     * Kiedy najwcześniej wolno wziąć to zlecenie do obróbki.
     *
     * Pełni dwie role naraz: nośnika backoffu po porażce i rezerwacji wiersza przez
     * instancję, która właśnie zabiera się do tłumaczenia (patrz TranslationJobWorker).
     */
    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    /**
     * @param expectedCacheHit czy w chwili przyjęcia istniał już gotowy wynik tej treści -
     *                         decyduje o billedChars. To PRZEWIDYWANIE, nie fakt: gotowy wiersz
     *                         może zniknąć (skasowany przez użytkownika, wygaszony przez
     *                         retencję) zanim worker weźmie zlecenie, dlatego worker zapisuje
     *                         przy wyniku wartość ostateczną.
     */
    public TranslationJob(User user,
                          String originalFilename,
                          TargetLanguage targetLang,
                          String sourceObjectKey,
                          String contentHash,
                          int charCount,
                          Instant now,
                          boolean expectedCacheHit) {
        this.user = user;
        this.originalFilename = originalFilename;
        this.targetLang = targetLang;
        this.sourceObjectKey = sourceObjectKey;
        this.contentHash = contentHash;
        // charCount podawany z zewnątrz, bo treści już tutaj nie ma - plik leży
        // w magazynie obiektowym, a długość policzył ten, kto go tam wgrał.
        this.charCount = charCount;
        this.billedChars = expectedCacheHit ? 0 : charCount;
        this.status = TranslationStatus.PENDING;
        this.attempts = 0;
        // Gotowe do wzięcia natychmiast - worker zabierze je przy najbliższym cyklu
        this.nextAttemptAt = now;
        this.createdAt = now;
    }
}
