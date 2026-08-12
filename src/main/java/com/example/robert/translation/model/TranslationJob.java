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
 * UWAGA przy odczytach: encja niesie dwie kolumny tekstowe po ~1 MB. Pobieranie jej przez
 * findById tylko po to, żeby pokazać status albo nazwę pliku, wciąga całą treść źródła
 * i wyniku - przy liście zleceń użytkownika to dziesiątki megabajtów na jedno żądanie.
 * Dlatego odczyty prezentacyjne idą przez projekcje w TranslationJobRepository, a nie
 * przez tę klasę.
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

    // Długości muszą być lustrzane wobec changesetu 0007. Powyżej maksymalnej długości
    // varchara dialektu Hibernate oczekuje CLOB-a i ddl-auto: validate wywala start -
    // to jest górna granica dla podnoszenia limitu uploadu.
    @Column(name = "source_content", nullable = false, length = 262_144)
    private String sourceContent;

    @Column(name = "result_content", length = 524_288)
    private String resultContent;

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

    /** Liczba znaków źródła - pod dobowy limit użytkownika, bez czytania całej treści. */
    @Column(name = "char_count", nullable = false)
    private int charCount;

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

    public TranslationJob(User user,
                          String originalFilename,
                          TargetLanguage targetLang,
                          String sourceContent,
                          String contentHash,
                          Instant now) {
        this.user = user;
        this.originalFilename = originalFilename;
        this.targetLang = targetLang;
        this.sourceContent = sourceContent;
        this.contentHash = contentHash;
        this.charCount = sourceContent.length();
        this.status = TranslationStatus.PENDING;
        this.attempts = 0;
        // Gotowe do wzięcia natychmiast - worker zabierze je przy najbliższym cyklu
        this.nextAttemptAt = now;
        this.createdAt = now;
    }
}
