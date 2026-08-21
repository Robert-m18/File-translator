/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.filetranslator.translation.model;

import com.example.filetranslator.translation.TranslationProperties;
import com.example.filetranslator.user.model.User;
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
 * Jedno zlecenie tłumaczenia pliku - wiersz kolejki i zarazem stan widoczny dla użytkownika.
 *
 * Wiersz nie zawiera treści plików: te leżą w magazynie obiektowym, a encja trzyma same klucze.
 * Odczyty prezentacyjne i tak korzystają z projekcji, ponieważ projekcja pilnuje również tego,
 * żeby przez API nie wyciekło pole, którego nikt nie zamierzał pokazywać.
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

    // Pobranie leniwe: właściciel potrzebny jest dopiero przy zamawianiu powiadomienia,
    // a większość zapytań filtruje po identyfikatorze użytkownika, nie po obiekcie.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "original_filename", nullable = false, length = 255)
    private String originalFilename;

    /** Język źródła wykrywa dostawca, więc pole pozostaje puste do udanego tłumaczenia. */
    @Column(name = "source_lang", length = 10)
    private String sourceLang;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_lang", nullable = false, length = 10)
    private TargetLanguage targetLang;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TranslationStatus status = TranslationStatus.PENDING;

    /**
     * Klucz pliku źródłowego w magazynie obiektowym - klucz, nie adres URL, żeby zmiana
     * dostawcy magazynu, regionu czy domeny nie unieważniała zapisanych wartości.
     *
     * Ustawiany w konstruktorze, ponieważ obiekt zapisywany jest przed wstawieniem wiersza:
     * wiersz bez pliku to zlecenie, które nigdy się nie wykona, a osierocony plik to tylko
     * zajęte miejsce, które usuwa reguła wygasania na kubełku.
     */
    @Column(name = "source_object_key", nullable = false, length = 512)
    private String sourceObjectKey;

    /** Klucz wyniku - pusty do chwili udanego tłumaczenia. */
    @Column(name = "result_object_key", length = 512)
    private String resultObjectKey;

    /**
     * Format pliku rozpoznany po zawartości przy wgrywaniu, nie po nazwie.
     *
     * Decyduje również o tym, którym API dostawcy tłumaczyć: tekst idzie interfejsem tekstowym,
     * formaty binarne dokumentowym.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "file_type", nullable = false, length = 10)
    private FileType fileType = FileType.TXT;

    /**
     * Uchwyt do dokumentu wgranego u dostawcy - pusty dla zleceń tekstowych i do chwili wgrania.
     *
     * Zapisany po to, żeby rezerwacja wygasła w trakcie tłumaczenia pozwalała wrócić do
     * odpytywania o gotowy wynik zamiast wgrywać dokument i płacić za niego drugi raz.
     */
    @Column(name = "provider_document_id", length = 255)
    private String providerDocumentId;

    /** Sekret wystawiony przez dostawcę razem z identyfikatorem. Nie trafia do logów. */
    @Column(name = "provider_document_key", length = 255)
    private String providerDocumentKey;

    /**
     * Odcisk treści źródłowej (SHA-256 zapisany szesnastkowo) - klucz deduplikacji.
     *
     * Pole dopuszcza wartość pustą wyłącznie ze względu na wiersze sprzed wprowadzenia
     * deduplikacji, których nie było czym wypełnić w przenośnym SQL-u. Każde nowe zlecenie ma
     * odcisk ustawiony w konstruktorze.
     */
    @Column(name = "content_hash", length = 64)
    private String contentHash;

    /**
     * Dostawca, który wykonał tłumaczenie - znany dopiero przy zapisie wyniku.
     *
     * Wchodzi do klucza deduplikacji razem z odciskiem treści i językiem docelowym. Bez niego
     * wynik wykonany przez atrapę zaspokoiłby zlecenie kierowane do prawdziwego dostawcy, co jest
     * jedynym przypadkiem, w którym deduplikacja zwraca wynik błędny, a nie tylko szybki.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "provider", length = 20)
    private TranslationProperties.Provider provider;

    /** Liczba znaków źródła - długość pliku pokazywana użytkownikowi. */
    @Column(name = "char_count", nullable = false)
    private int charCount;

    /**
     * Znaki faktycznie wydane u dostawcy - to na nich, a nie na długości pliku, liczy się dobowy
     * limit.
     *
     * Zlecenie zaspokojone z cache'a ma tu zero, bo u dostawcy nie kosztowało ani znaku.
     * Rozdzielenie obu wartości jest konieczne, ponieważ po fakcie wiersz zaspokojony z cache'a
     * jest nieodróżnialny od zwykłego - pole dostawcy wypełnia się tak samo, bo wchodzi do klucza
     * deduplikacji.
     *
     * Wartość ustawiana przy przyjęciu zlecenia jest przewidywaniem, a wykonawca koryguje ją przy
     * zapisie wyniku, gdy wiadomo już, czy dostawca był wołany.
     */
    @Column(name = "billed_chars", nullable = false)
    private int billedChars;

    /** Liczy podejścia, czyli rezerwacje wiersza, a nie potwierdzone porażki. */
    @Column(nullable = false)
    private int attempts = 0;

    /**
     * Najwcześniejszy moment, w którym wolno wziąć to zlecenie do obróbki.
     *
     * Pole pełni dwie role naraz: niesie backoff po porażce i stanowi rezerwację wiersza przez
     * instancję, która właśnie zabiera się do tłumaczenia. Dzięki temu zlecenie porzucone przez
     * proces, który padł, wraca do obiegu samoczynnie.
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
     *                         decyduje o liczbie znaków wliczanych do limitu. Jest to
     *                         przewidywanie, a nie fakt: gotowy wiersz może zniknąć, zanim
     *                         wykonawca weźmie zlecenie, dlatego wartość ostateczną zapisuje
     *                         wykonawca razem z wynikiem
     */
    public TranslationJob(User user,
                          String originalFilename,
                          TargetLanguage targetLang,
                          FileType fileType,
                          String sourceObjectKey,
                          String contentHash,
                          int charCount,
                          Instant now,
                          boolean expectedCacheHit) {
        this.user = user;
        this.originalFilename = originalFilename;
        this.targetLang = targetLang;
        this.fileType = fileType;
        this.sourceObjectKey = sourceObjectKey;
        this.contentHash = contentHash;
        // Liczba znaków podawana z zewnątrz, ponieważ treści tu nie ma - plik leży w magazynie
        // obiektowym, a długość policzył ten, kto go tam wgrał.
        this.charCount = charCount;
        this.billedChars = expectedCacheHit ? 0 : charCount;
        this.status = TranslationStatus.PENDING;
        this.attempts = 0;
        // Zlecenie jest gotowe do wzięcia natychmiast - wykonawca zabierze je w najbliższym cyklu.
        this.nextAttemptAt = now;
        this.createdAt = now;
    }
}
