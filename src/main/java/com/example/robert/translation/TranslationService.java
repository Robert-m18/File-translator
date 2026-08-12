/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.robert.translation;

import com.example.robert.common.time.DbClock;
import com.example.robert.translation.dto.TranslationJobResponse;
import com.example.robert.translation.dto.TranslationResultView;
import com.example.robert.translation.exception.TranslationJobNotFoundException;
import com.example.robert.translation.exception.TranslationNotReadyException;
import com.example.robert.translation.exception.TranslationQuotaExceededException;
import com.example.robert.translation.model.TargetLanguage;
import com.example.robert.translation.model.TranslationJob;
import com.example.robert.translation.model.TranslationStatus;
import com.example.robert.translation.repository.TranslationJobRepository;
import com.example.robert.user.model.User;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * Przyjmowanie zleceń tłumaczenia i odczyty dla ich właściciela.
 *
 * Sam akt tłumaczenia dzieje się gdzie indziej - w TranslationJobWorker, poza żądaniem HTTP.
 * Ta klasa zapisuje zlecenie i oddaje sterowanie; kolejka jest granicą między tym, co widzi
 * użytkownik, a rozmową z zewnętrznym API.
 *
 * WSZYSTKIE odczyty biorą userId do zapytania, nigdy "pobierz i porównaj" - uzasadnienie
 * przy TranslationJobNotFoundException.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TranslationService {

    private static final Duration QUOTA_WINDOW = Duration.ofDays(1);

    private final TranslationJobRepository repository;
    private final TranslationProperties properties;
    private final MeterRegistry meters;

    /**
     * Przyjmuje zlecenie i oddaje jego stan początkowy.
     *
     * @Transactional obejmuje sprawdzenie limitu i zapis - inaczej dwa równoległe żądania
     * tego samego użytkownika mogłyby oba zobaczyć wolny limit i oba się zmieścić. To nie
     * jest pełna ochrona przed wyścigiem (do tego trzeba by blokady na wierszu użytkownika),
     * ale przy limicie chroniącym budżet, a nie bezpieczeństwo, przekroczenie o jeden plik
     * jest akceptowalne, a blokada na koncie użytkownika przy KAŻDYM zleceniu nie.
     */
    @Transactional
    public TranslationJobResponse submit(User owner, UploadedTextFile file, TargetLanguage targetLang) {
        Instant now = DbClock.now();
        String contentHash = file.contentHash();

        /*
         * Dobowy limit znaków chroni KONTO U DOSTAWCY, a zlecenie, które zostanie zaspokojone
         * z cache'a, nie kosztuje tam ani znaku - naliczanie go byłoby karą za operację, która
         * nic nie kosztuje.
         *
         * Wygląda to na dziurę: ten sam plik w pętli przechodziłby bez limitu w nieskończoność.
         * Zamyka ją limiter żądań, który ma na POST /translations osobną politykę (30/h,
         * application.yml), więc sufit takiej pętli to 30 zleceń na godzinę. Bez tego zdania
         * pominięcie limitu czyta się jak przeoczenie, a nie jak decyzja.
         *
         * Sprawdzenie jest tanie: existsCached idzie po indeksie idx_translation_jobs_cache
         * i NIE czyta treści wyniku. Samo kopiowanie wyniku dzieje się później, w workerze.
         */
        if (!repository.existsCached(owner.getId(), contentHash, targetLang, properties.provider())) {
            enforceDailyQuota(owner, file.content().length(), now);
        }

        TranslationJob job = repository.save(new TranslationJob(
                owner, file.filename(), targetLang, file.content(), contentHash, now));

        // Zestawiony z translation.jobs.finished daje długość kolejki bez odpytywania bazy:
        // rozjazd między tymi dwoma licznikami to zlecenia, które utknęły.
        meters.counter("translation.jobs.submitted", "target", targetLang.name()).increment();

        // Bez nazwy pliku i bez adresu email w logu - to dane użytkownika. Powiązanie
        // z żądaniem daje traceId, a id zlecenia jest tym samym uchwytem, co id wiersza
        // skrzynki nadawczej: worker pracuje na wątku bez MDC, więc bez id po obu stronach
        // łańcuch "żądanie -> wynik tłumaczenia" urywa się w tym miejscu.
        log.info("Przyjęto zlecenie tłumaczenia (id={}, znaków={}, język={})",
                job.getId(), job.getCharCount(), targetLang);

        return toResponse(job);
    }

    private void enforceDailyQuota(User owner, int requestedChars, Instant now) {
        int limit = properties.dailyCharLimit();
        long used = repository.sumCharCountSince(owner.getId(), now.minus(QUOTA_WINDOW));
        long remaining = Math.max(0, limit - used);

        if (requestedChars > remaining) {
            log.warn("Odrzucono zlecenie - dobowy limit znaków wyczerpany (użyte={}, limit={})",
                    used, limit);
            throw new TranslationQuotaExceededException(remaining, limit);
        }
    }

    @Transactional(readOnly = true)
    public Page<TranslationJobResponse> listOwn(User owner, Pageable pageable) {
        return repository.findSummaries(owner.getId(), pageable);
    }

    @Transactional(readOnly = true)
    public TranslationJobResponse getOwn(User owner, Long jobId) {
        return repository.findSummary(jobId, owner.getId())
                .orElseThrow(TranslationJobNotFoundException::new);
    }

    /**
     * Wynik gotowy do pobrania.
     *
     * Rozróżnia trzy sytuacje, i to rozróżnienie jest całą wartością tej metody: nie ma
     * takiego zlecenia (404), jest, ale nie jest gotowe (409 ze statusem, żeby front wiedział,
     * czy dalej odpytywać), jest i gotowe.
     */
    @Transactional(readOnly = true)
    public TranslationResultView getOwnResult(User owner, Long jobId) {
        TranslationResultView view = repository.findResult(jobId, owner.getId())
                .orElseThrow(TranslationJobNotFoundException::new);

        if (view.status() != TranslationStatus.DONE || view.resultContent() == null) {
            throw new TranslationNotReadyException(view.status());
        }
        return view;
    }

    @Transactional
    public void deleteOwn(User owner, Long jobId) {
        if (repository.deleteOwned(jobId, owner.getId()) == 0) {
            throw new TranslationJobNotFoundException();
        }
        log.info("Usunięto zlecenie tłumaczenia (id={})", jobId);
    }

    private TranslationJobResponse toResponse(TranslationJob job) {
        return new TranslationJobResponse(
                job.getId(),
                job.getOriginalFilename(),
                job.getSourceLang(),
                job.getTargetLang(),
                job.getStatus(),
                job.getCharCount(),
                job.getCreatedAt(),
                job.getCompletedAt());
    }
}
