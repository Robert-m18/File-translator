/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.robert.translation;

import com.example.robert.common.time.DbClock;
import com.example.robert.translation.model.TranslationJob;
import com.example.robert.translation.provider.TranslationProvider;
import com.example.robert.translation.provider.TranslationProviderException;
import com.example.robert.translation.provider.TranslationResult;
import com.example.robert.translation.repository.TranslationJobRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Wykonuje zlecenia tłumaczenia leżące w kolejce.
 *
 * Pętla jednego cyklu:
 *   1. rezerwacja paczki: odczyt kandydatów (next_attempt_at <= teraz) blokujący wiersze
 *      dla tej instancji i od razu UPDATE odsuwający next_attempt_at oraz ustawiający
 *      status PROCESSING - wszystko w jednej krótkiej transakcji,
 *   2. tłumaczenie zarezerwowanych zleceń RÓWNOLEGLE,
 *   3. zapis wyniku każdego z osobna: DONE, ponowienie z backoffem albo FAILED.
 *
 * Wzorzec jest CELOWO ten sam co w OutboxPublisher - dwa różne mechanizmy kolejkowania
 * w jednej aplikacji kosztowałyby więcej niż to, co dałaby jakakolwiek różnica. Tam też
 * jest zapisane pełne uzasadnienie rezerwacji przez okno czasowe i braku transakcji
 * rozciągniętej na operację zewnętrzną.
 *
 * JEDNA RÓŻNICA WOBEC SKRZYNKI NADAWCZEJ: tutaj jest status PROCESSING. W outboxie osobny
 * stan "w trakcie" strandowałby wiersz po padnięciu procesu, więc go nie ma. Tutaj status
 * jest częścią kontraktu API, ale o tym, czy wolno wziąć zlecenie, decyduje WYŁĄCZNIE
 * next_attempt_at - dzięki czemu jedno pole niesie rezerwację, drugie prezentację, i żadne
 * z nich nie musi robić obu rzeczy naraz.
 *
 * DLACZEGO WŁASNA PULA WĄTKÓW, A NIE WĄTKI WIRTUALNE: chodzi właśnie o SUFIT liczby
 * równoczesnych żądań do dostawcy. Wątki wirtualne żadnego sufitu nie dają, a dostawcy
 * limitują równoległość i odpowiadają 429 po jego przekroczeniu - nieograniczona pula
 * zamieniłaby szczyt ruchu w serię ponowień.
 *
 * GWARANCJA: at-least-once. Jeśli proces padnie między udanym tłumaczeniem a zapisem
 * wyniku, zlecenie wróci po upływie rezerwacji i zostanie przetłumaczone drugi raz. Kosztuje
 * to znaki u dostawcy, ale użytkownik dostaje poprawny wynik - odwrotna gwarancja
 * (at-most-once) oznaczałaby zlecenia cicho porzucone.
 */
@Slf4j
@Component
public class TranslationJobWorker {

    private final TranslationJobRepository repository;
    private final TranslationProvider provider;
    private final TranslationEvents events;
    private final TranslationProperties properties;
    private final TransactionTemplate shortTransaction;
    private final ExecutorService translators;
    private final MeterRegistry meters;

    public TranslationJobWorker(TranslationJobRepository repository,
                                TranslationProvider provider,
                                TranslationEvents events,
                                TranslationProperties properties,
                                PlatformTransactionManager transactionManager,
                                MeterRegistry meters) {
        this.repository = repository;
        this.provider = provider;
        this.events = events;
        this.properties = properties;
        this.meters = meters;

        this.shortTransaction = new TransactionTemplate(transactionManager);
        this.shortTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        AtomicInteger counter = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "translation-worker-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        this.translators = Executors.newFixedThreadPool(properties.concurrency(), factory);
    }

    @Scheduled(fixedDelayString = "${app.translation.poll-interval:PT2S}")
    public void processScheduled() {
        if (!properties.enabled()) {
            return;
        }
        processBatch();
    }

    /**
     * Jeden cykl. Publiczna i niezależna od harmonogramu, żeby testy mogły ją wywołać wprost,
     * bez czekania i bez zgadywania, co zdążyło się wykonać w tle.
     *
     * Wraca dopiero, gdy każde zlecenie z paczki ma zapisany wynik.
     *
     * @return liczba zleceń przetłumaczonych w tym cyklu
     */
    public int processBatch() {
        List<TranslationJob> claimed = claimBatch();

        if (claimed.isEmpty()) {
            return 0;
        }
        // Jedno zlecenie nie ma z czym się zrównoleglać - przeskok na inny wątek byłby
        // tu czystym kosztem, a to najczęstszy przypadek przy realnym ruchu.
        if (claimed.size() == 1) {
            return translate(claimed.get(0)) ? 1 : 0;
        }

        List<CompletableFuture<Boolean>> results = claimed.stream()
                .map(job -> CompletableFuture.supplyAsync(() -> translate(job), translators))
                .toList();

        return (int) results.stream().filter(CompletableFuture::join).count();
    }

    /**
     * Odczytuje i rezerwuje paczkę w jednej transakcji. Blokada z findClaimable
     * (FOR UPDATE SKIP LOCKED) trzyma się do commitu, więc żadna inna instancja nie
     * odczyta w tym czasie tych samych wierszy.
     *
     * @return zlecenia zarezerwowane dla tej instancji, w stanie sprzed rezerwacji
     */
    private List<TranslationJob> claimBatch() {
        Instant now = DbClock.now();

        List<TranslationJob> claimed = shortTransaction.execute(status -> {
            List<TranslationJob> candidates =
                    repository.findClaimable(now, Limit.of(properties.batchSize()));

            if (candidates.isEmpty()) {
                return List.of();
            }

            repository.claim(
                    candidates.stream().map(TranslationJob::getId).toList(),
                    now,
                    now.plus(properties.claimTimeout()));

            return candidates;
        });

        return claimed == null ? List.of() : claimed;
    }

    /**
     * Tłumaczy jedno zarezerwowane zlecenie i zapisuje wynik. Nigdy nie rzuca - leci na
     * wątku roboczym, gdzie wyjątek nie ma dokąd polecieć, a jedno zepsute zlecenie nie może
     * przerwać pozostałych z paczki.
     */
    private boolean translate(TranslationJob job) {
        int attempt = job.getAttempts() + 1; // claim już podbił licznik w bazie
        long startedAt = System.nanoTime();

        try {
            TranslationResult result = timeProviderCall(() ->
                    provider.translate(job.getSourceContent(), job.getTargetLang()));
            markDone(job, result);

            countOutcome("done");
            // Znaki, a nie zlecenia: u dostawcy płaci się za znaki, więc to jest jedyna
            // metryka, z której da się odczytać, jak blisko limitu konta jesteśmy.
            meters.counter("translation.chars.translated").increment(job.getCharCount());

            log.info("Przetłumaczono zlecenie (id={}, znaków={}, język={}, podejście={}, czas={}ms)",
                    job.getId(), job.getCharCount(), job.getTargetLang(), attempt,
                    Duration.ofNanos(System.nanoTime() - startedAt).toMillis());
            return true;

        } catch (TranslationProviderException e) {
            recordFailure(job, attempt, e.getCode(), e.isRetryable(), e);
            return false;

        } catch (Exception e) {
            // Nieznany błąd traktujemy jako przejściowy: awaria po naszej stronie (np. brak
            // połączenia z bazą) minie, a porzucenie zlecenia po pierwszej takiej porażce
            // byłoby nieodwracalne. Fałszywe ponowienie kosztuje znaki, fałszywe poddanie się
            // kosztuje pracę użytkownika.
            recordFailure(job, attempt, "TRANSLATION_UNEXPECTED_ERROR", true, e);
            return false;
        }
    }

    /**
     * Zapis wyniku i zamówienie powiadomienia w JEDNEJ transakcji.
     *
     * To nie jest szczegół: cała wartość skrzynki nadawczej polega na tym, że zamiar wysłania
     * maila commituje się razem z operacją, którą opisuje, albo nie commituje się wcale.
     * Zamówienie maila poza tą transakcją znaczyłoby, że wycofany zapis wyniku zostawia
     * użytkownikowi wiadomość "tłumaczenie gotowe" prowadzącą do zlecenia, które dalej czeka.
     */
    private void markDone(TranslationJob job, TranslationResult result) {
        shortTransaction.execute(status -> {
            repository.markDone(job.getId(), result.translatedText(),
                    result.detectedSourceLanguage(), DbClock.now());
            // Dane do powiadomienia biorą się z osobnego zapytania, a nie z job.getUser():
            // encja pochodzi z zamkniętej już transakcji rezerwacji, więc leniwe pole user
            // jest tam martwym proxy. Szczegóły przy findCompletedEvent.
            repository.findCompletedEvent(job.getId()).ifPresent(events::completed);
            return null;
        });
    }

    private void recordFailure(TranslationJob job, int attempt, String code, boolean retryable, Exception cause) {
        String error = trim(code + ": " + cause.getMessage());

        try {
            /*
             * Błąd TRWAŁY kończy zlecenie od razu, bez czekania na wyczerpanie prób.
             * To jest cała wartość flagi retryable: nieprawidłowy klucz API albo
             * nieobsługiwany język będą wracać identycznie za każdym razem, więc pełny
             * backoff oznaczałby kilkanaście minut zwłoki po to, żeby dojść do wniosku
             * znanego już z pierwszej odpowiedzi dostawcy.
             */
            if (!retryable) {
                shortTransaction.execute(status -> repository.markFailed(job.getId(), error, DbClock.now()));
                countOutcome("failed");
                log.error("Zlecenie odrzucone trwale (id={}, podejście={}): {}",
                        job.getId(), attempt, error);
                return;
            }

            if (attempt >= properties.maxAttempts()) {
                shortTransaction.execute(status -> repository.markFailed(job.getId(), error, DbClock.now()));
                countOutcome("failed");
                // ERROR, nie WARN: zlecenie użytkownika przepadło i wymaga człowieka.
                log.error("Zlecenie porzucone po {} podejściach (id={}): {}",
                        attempt, job.getId(), error);
                return;
            }

            Duration delay = properties.retryBackoff().multipliedBy(1L << (attempt - 1));
            // Obcięcie jak wszędzie w tej ścieżce: ten znacznik jest potem porównywany
            // warunkiem "<= now", więc zaokrąglenie w górę odsuwałoby ponowienie o mikrosekundę
            // i - przy dostatecznie krótkim backoffie - powtarzało ten sam wyścig (patrz DbClock).
            Instant nextAttempt = DbClock.truncate(Instant.now().plus(delay));

            shortTransaction.execute(status -> repository.markRetry(job.getId(), nextAttempt, error));
            log.warn("Nie udało się przetłumaczyć zlecenia (id={}, podejście={}/{}), ponowienie za {}: {}",
                    job.getId(), attempt, properties.maxAttempts(), delay, error);

        } catch (Exception e) {
            // Padł zapis wyniku, a nie samo tłumaczenie. Zlecenie zostaje z rezerwacją, więc
            // wróci samo po jej upływie - to jest dokładnie ten scenariusz, dla którego
            // rezerwacja jest oknem czasowym, a nie statusem.
            log.error("Nie udało się zapisać wyniku tłumaczenia (id={}): {}", job.getId(), e.toString());
        }
    }

    /**
     * Czas rozmowy z dostawcą, mierzony osobno od reszty cyklu.
     *
     * To jest jedyna metryka odpowiadająca na pytanie "czy to my jesteśmy wolni, czy oni" -
     * bez niej wolne tłumaczenia wyglądają identycznie niezależnie od tego, czy przyczyną
     * jest dostawca, czy nasza kolejka. Tag z nazwą dostawcy, żeby po przełączeniu z echo
     * na deepl dało się porównać jedno z drugim.
     */
    private TranslationResult timeProviderCall(java.util.function.Supplier<TranslationResult> call) {
        return Timer.builder("translation.provider.duration")
                .tag("provider", properties.provider().name().toLowerCase(Locale.ROOT))
                .register(meters)
                .record(call);
    }

    /**
     * Licznik zakończeń z tagiem wyniku. Tag, a nie trzy osobne liczniki: dzięki temu
     * "ile zleceń w ogóle" i "jaki odsetek padł" to jedno zapytanie w Prometheusie.
     */
    private void countOutcome(String outcome) {
        meters.counter("translation.jobs.finished", "outcome", outcome).increment();
    }

    /** Kolumna last_error ma 500 znaków - dłuższy komunikat wywaliłby zapis wyniku. */
    private String trim(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 500 ? value : value.substring(0, 500);
    }

    /**
     * Przy zamykaniu aplikacji dajemy trwającym tłumaczeniom dokończyć. Ubicie ich w locie
     * oznaczałoby znaki zużyte u dostawcy bez zapisanego wyniku, czyli powtórne tłumaczenie
     * po restarcie - dopuszczalne, ale niepotrzebne, skoro wystarczy chwilę poczekać.
     */
    @PreDestroy
    void shutdown() throws InterruptedException {
        translators.shutdown();
        if (!translators.awaitTermination(30, TimeUnit.SECONDS)) {
            log.warn("Tłumaczenia nie zakończyły się w 30 s - przerywam");
            translators.shutdownNow();
        }
    }
}
