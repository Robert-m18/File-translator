/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.filetranslator.translation;

import com.example.filetranslator.common.time.DbClock;
import com.example.filetranslator.translation.dto.TranslationCacheHit;
import com.example.filetranslator.translation.model.TranslationJob;
import com.example.filetranslator.translation.provider.DocumentHandle;
import com.example.filetranslator.translation.provider.DocumentStatus;
import com.example.filetranslator.translation.provider.DocumentUnavailableException;
import com.example.filetranslator.translation.provider.TranslationProvider;
import com.example.filetranslator.translation.provider.TranslationProviderException;
import com.example.filetranslator.translation.provider.TranslationResult;
import com.example.filetranslator.translation.repository.TranslationJobRepository;
import com.example.filetranslator.translation.storage.ObjectKeys;
import com.example.filetranslator.translation.storage.ObjectStore;
import io.micrometer.core.instrument.Counter;
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

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Wykonawca zleceń tłumaczenia czekających w kolejce (tabela translation_jobs).
 *
 * Cykl składa się z trzech kroków: rezerwacji paczki zleceń w jednej krótkiej transakcji,
 * równoległego tłumaczenia zarezerwowanych zleceń i zapisu każdego wyniku osobno
 * (DONE, ponowienie z backoffem albo FAILED).
 *
 * Rezerwacja opiera się na oknie czasowym (next_attempt_at odsunięty w przyszłość), a nie na
 * statusie "w trakcie". Zaletą jest samoczynne odzyskiwanie zleceń po awarii procesu: wiersz
 * staje się ponownie dostępny po upływie okna, bez zadania naprawczego. Status PROCESSING
 * istnieje wyłącznie dla API i nie bierze udziału w decyzji, czy zlecenie wolno pobrać -
 * jedno pole odpowiada za rezerwację, drugie za prezentację.
 *
 * Tłumaczenia wykonuje pula wątków o stałym rozmiarze, a nie wątki wirtualne, ponieważ celem
 * jest sufit liczby równoczesnych żądań do dostawcy. Dostawcy limitują równoległość i zwracają
 * 429 po jej przekroczeniu, więc pula bez ograniczenia zamieniłaby szczyt ruchu w serię ponowień.
 *
 * Gwarancja wykonania to at-least-once: awaria między udanym tłumaczeniem a zapisem wyniku
 * powoduje powtórzenie zlecenia po upływie rezerwacji. Kosztuje to znaki u dostawcy, ale
 * użytkownik otrzymuje poprawny wynik; odwrotna gwarancja oznaczałaby zlecenia porzucane po cichu.
 */
@Slf4j
@Component
public class TranslationJobWorker {

    /** Rozszerzenie i typ treści dla tłumaczeń tekstowych - zgodne z zapisem pliku źródłowego. */
    private static final String TEXT_EXTENSION = ".txt";
    private static final String TEXT_CONTENT_TYPE = "text/plain; charset=UTF-8";

    private final TranslationJobRepository repository;
    private final TranslationProvider provider;
    private final TranslationEvents events;
    private final TranslationProperties properties;
    private final ObjectStore objectStore;
    private final TransactionTemplate shortTransaction;
    private final ExecutorService translators;
    private final MeterRegistry meters;

    public TranslationJobWorker(TranslationJobRepository repository,
                                TranslationProvider provider,
                                TranslationEvents events,
                                TranslationProperties properties,
                                ObjectStore objectStore,
                                PlatformTransactionManager transactionManager,
                                MeterRegistry meters) {
        this.repository = repository;
        this.provider = provider;
        this.events = events;
        this.properties = properties;
        this.objectStore = objectStore;
        this.meters = meters;
        registerMeters();

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
     * Wykonuje jeden pełny cykl i wraca dopiero wtedy, gdy każde zlecenie z paczki ma zapisany
     * wynik. Metoda jest publiczna i niezależna od harmonogramu, dzięki czemu testy wywołują ją
     * wprost, bez czekania i bez zgadywania, co zdążyło wykonać się w tle.
     *
     * @return liczba zleceń przetłumaczonych w tym cyklu
     */
    public int processBatch() {
        List<TranslationJob> claimed = claimBatch();

        if (claimed.isEmpty()) {
            return 0;
        }
        // Pojedyncze zlecenie nie ma z czym się zrównoleglać, a przeskok na inny wątek kosztuje.
        // Przy typowym ruchu jest to najczęstszy przypadek.
        if (claimed.size() == 1) {
            return translate(claimed.get(0)) ? 1 : 0;
        }

        List<CompletableFuture<Boolean>> results = claimed.stream()
                .map(job -> CompletableFuture.supplyAsync(() -> translate(job), translators))
                .toList();

        return (int) results.stream().filter(CompletableFuture::join).count();
    }

    /**
     * Odczytuje i rezerwuje paczkę zleceń w jednej transakcji.
     *
     * Blokada założona przez findClaimable (FOR UPDATE SKIP LOCKED) trwa do commitu, więc dwie
     * instancje aplikacji pobierają rozłączne zbiory wierszy zamiast konkurować o te same.
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
     * Tłumaczy jedno zarezerwowane zlecenie i zapisuje jego wynik.
     *
     * Metoda nie wypuszcza wyjątków: wykonuje się na wątku roboczym, gdzie wyjątek nie miałby
     * dokąd polecieć, a pojedyncze zepsute zlecenie nie może przerwać pozostałych z paczki.
     */
    private boolean translate(TranslationJob job) {
        int attempt = job.getAttempts() + 1; // claim podbił już licznik w bazie
        long startedAt = System.nanoTime();

        try {
            /*
             * Deduplikacja: ta sama treść, język docelowy i dostawca u tego samego użytkownika
             * oznaczają gotowy wynik, za który nie trzeba płacić drugi raz.
             *
             * Odczyt jest tutaj, a nie przy przyjmowaniu zlecenia, ponieważ ta ścieżka i tak
             * biegnie poza wątkiem HTTP - kopiowanie wyniku nie wydłuża czasu odpowiedzi API.
             * Przy przyjmowaniu sprawdzany jest wyłącznie sam fakt trafienia, żeby nie naliczać
             * dobowego limitu za operację, która nic u dostawcy nie kosztuje.
             *
             * Wyścig dwóch identycznych plików zleconych w tej samej chwili pozostaje otwarty:
             * oba spudłują i oba zawołają dostawcę. Zamknięcie go wymagałoby blokady na odcisku
             * treści, czyli szeregowania zleceń - cena wyższa niż jedno nadmiarowe tłumaczenie
             * w rzadkim przypadku.
             */
            Optional<TranslationCacheHit> cached = findCached(job);
            String resultKey = ObjectKeys.resultKey(
                    ObjectKeys.prefixOf(job.getSourceObjectKey()), job.getFileType().extension());

            if (cached.isPresent()) {
                /*
                 * Trafienie realizuje kopia po stronie magazynu - bajty nie przechodzą przez
                 * aplikację. Kopia zamiast wskazania dwóch zleceń na jeden obiekt daje każdemu
                 * zleceniu wyłączność na własny prefiks, dzięki czemu skasowanie zlecenia jest
                 * jednym wywołaniem i nie wymaga liczenia referencji.
                 *
                 * Obiekt powstaje przed zapisem klucza w wierszu - ta sama kolejność co przy
                 * przyjmowaniu zlecenia.
                 */
                objectStore.copy(cached.get().resultObjectKey(), resultKey);

                // Zero znaków do rozliczenia: dostawca nie był wołany, więc dobowy limit (suma
                // po billedChars) nie obciąża tego zlecenia ani teraz, ani przy następnych.
                markDone(job, resultKey, cached.get().sourceLang(), 0, cached.get().charCount());

                countOutcome("done");
                countCache("hit");
                // Znaki zaoszczędzone. Zestawione z translation.chars.translated odpowiadają na
                // pytanie, czy deduplikacja się opłaca.
                meters.counter("translation.chars.saved").increment(job.getCharCount());

                log.info("Zlecenie zaspokojone z cache'a, bez wywołania dostawcy "
                                + "(id={}, znaków={}, język={})",
                        job.getId(), job.getCharCount(), job.getTargetLang());
                return true;
            }
            countCache("miss");

            // Dokumenty idą osobną, asynchroniczną ścieżką i mogą nie zakończyć się w jednym
            // podejściu - patrz translateDocument.
            if (job.getFileType().usesDocumentApi()) {
                return translateDocument(job, resultKey, attempt, startedAt);
            }

            // Treść źródła pobierana z magazynu dopiero na wątku roboczym. Wiersz zlecenia niesie
            // sam klucz, dzięki czemu rezerwacja paczki nie czyta plików.
            String sourceContent = new String(
                    objectStore.read(job.getSourceObjectKey()), StandardCharsets.UTF_8);

            TranslationResult result = timeProviderCall(() ->
                    provider.translate(sourceContent, job.getTargetLang()));

            /*
             * Wynik trafia do magazynu przed zapisaniem klucza w wierszu. Awaria pomiędzy tymi
             * krokami zostawia obiekt bez wskazania, a zlecenie wraca po upływie rezerwacji
             * i nadpisuje ten sam klucz, bo prefiks zależy od zlecenia, a nie od podejścia.
             * Odwrotna kolejność dałaby wiersz DONE wskazujący na nieistniejący plik.
             */
            objectStore.put(resultKey, result.translatedText().getBytes(StandardCharsets.UTF_8),
                    job.getFileType().contentType());

            // Dostawca był wołany, więc znaki są wydane - również wtedy, gdy przy przyjęciu
            // zlecenie wyglądało na trafienie, a gotowy wiersz zniknął przed jego obróbką.
            markDone(job, resultKey, result.detectedSourceLanguage(),
                    job.getCharCount(), job.getCharCount());

            countOutcome("done");
            // Licznik znaków, a nie zleceń: u dostawcy płaci się za znaki, więc tylko z tej
            // metryki da się odczytać, jak blisko limitu konta jest aplikacja. Trafienie w cache
            // jej nie podbija, żeby zachowała znaczenie "znaki faktycznie wydane".
            meters.counter("translation.chars.translated").increment(job.getCharCount());

            log.info("Przetłumaczono zlecenie (id={}, znaków={}, język={}, podejście={}, czas={}ms)",
                    job.getId(), job.getCharCount(), job.getTargetLang(), attempt,
                    Duration.ofNanos(System.nanoTime() - startedAt).toMillis());
            return true;

        } catch (TranslationProviderException e) {
            recordFailure(job, attempt, e.getCode(), e.isRetryable(), e);
            return false;

        } catch (Exception e) {
            // Nieznany błąd traktowany jest jako przejściowy: awaria po stronie aplikacji
            // (na przykład brak połączenia z bazą) minie, a porzucenie zlecenia po pierwszej
            // takiej porażce jest nieodwracalne. Nadmiarowe ponowienie kosztuje znaki,
            // przedwczesne poddanie się kosztuje pracę użytkownika.
            recordFailure(job, attempt, "TRANSLATION_UNEXPECTED_ERROR", true, e);
            return false;
        }
    }

    /**
     * Wykonuje jedno podejście do dokumentu: wgranie (o ile jeszcze nie nastąpiło), sprawdzenie
     * stanu u dostawcy i pobranie wyniku.
     *
     * Metoda nie czeka na zakończenie tłumaczenia. Gdy dostawca jeszcze pracuje, zlecenie wraca
     * do kolejki z krótkim odstępem i nie traci podejścia (markPolling), dzięki czemu wątek
     * roboczy obsługuje w tym czasie inne zlecenia zamiast blokować się na oczekiwaniu.
     * Dokument tłumaczony dłużej niż okno rezerwacji wraca do tego samego miejsca dzięki
     * uchwytowi zapisanemu w wierszu, zamiast zostać wgrany i opłacony po raz drugi.
     *
     * Kolejność końcowych kroków - pobranie, zapis do magazynu, dopiero markDone - wynika
     * z tego, że dostawca pozwala pobrać dokument tylko raz i kasuje go zaraz potem. Awaria
     * między pobraniem a zapisem oznacza utratę opłaconego tłumaczenia, a jedynym wyjściem jest
     * wgranie od nowa; ta kolejność zwęża okno takiej utraty do minimum osiągalnego bez
     * transakcji rozpiętej na dostawcę.
     *
     * @return true, jeśli zlecenie jest zakończone; false, jeśli wróci jeszcze do kolejki
     */
    private boolean translateDocument(TranslationJob job, String resultKey, int attempt, long startedAt) {
        DocumentHandle existing = documentHandleOf(job);
        final DocumentHandle handle = existing != null ? existing : uploadAndRemember(job);

        DocumentStatus status;
        byte[] translated;
        try {
            status = provider.checkDocument(handle);

            if (status.inProgress()) {
                Instant nextPoll = DbClock.truncate(Instant.now().plus(properties.documentPollInterval()));
                shortTransaction.execute(tx -> repository.markPolling(job.getId(), nextPoll));
                log.debug("Dokument wciąż tłumaczony u dostawcy (id={}) - sprawdzę ponownie", job.getId());
                return false;
            }

            if (status.state() == DocumentStatus.State.ERROR) {
                // Błąd dotyczy tego konkretnego dokumentu i powtórzy się identycznie przy każdym
                // podejściu, więc zlecenie kończy się od razu - tak samo jak przy nieobsługiwanym
                // języku.
                throw new TranslationProviderException("TRANSLATION_DOCUMENT_REJECTED", false,
                        status.errorMessage() == null ? "Dostawca odrzucił dokument" : status.errorMessage());
            }

            translated = timeProviderCall(() -> provider.downloadDocument(handle));

        } catch (DocumentUnavailableException e) {
            /*
             * Dokumentu nie ma już u dostawcy. Wyczyszczenie uchwytu jest konieczne, ponieważ
             * bez tego każde kolejne podejście pytałoby o ten sam nieistniejący dokument aż do
             * wyczerpania prób - zlecenie umarłoby, mimo że wystarczy zacząć od nowa. Po
             * wyczyszczeniu ponowienie wgrywa dokument ponownie, co kosztuje drugie rozliczenie;
             * to cena jednorazowego pobrania po stronie dostawcy.
             */
            shortTransaction.execute(tx -> repository.clearDocumentHandle(job.getId()));
            log.warn("Dokument zniknął u dostawcy (id={}) - kolejne podejście wgra go od nowa", job.getId());
            throw e;
        }

        objectStore.put(resultKey, translated, job.getFileType().contentType());

        // Liczbę znaków dokumentu podaje dostawca dopiero w wyniku. Trafia jednocześnie do
        // rozliczenia (billedChars) i do pola widocznego dla użytkownika (charCount), bo dla
        // dokumentu jest to ta sama wartość - aplikacja nie ma własnego sposobu jej policzenia.
        int billed = status.billedCharacters() == null ? 0 : status.billedCharacters();
        // sourceLang zostaje pusty: dokumentowe API nie zwraca wykrytego języka źródła.
        markDone(job, resultKey, null, billed, billed);

        countOutcome("done");
        meters.counter("translation.chars.translated").increment(billed);

        log.info("Przetłumaczono dokument (id={}, format={}, znaków={}, język={}, podejście={}, czas={}ms)",
                job.getId(), job.getFileType(), billed, job.getTargetLang(), attempt,
                Duration.ofNanos(System.nanoTime() - startedAt).toMillis());
        return true;
    }

    private DocumentHandle documentHandleOf(TranslationJob job) {
        return job.getProviderDocumentId() == null || job.getProviderDocumentKey() == null
                ? null
                : new DocumentHandle(job.getProviderDocumentId(), job.getProviderDocumentKey());
    }

    /**
     * Wgrywa dokument do dostawcy i natychmiast zatwierdza uchwyt w osobnej transakcji.
     *
     * Zapis następuje od razu, a nie razem z wynikiem, ponieważ od chwili wgrania dokument jest
     * już opłacony. Zapisany uchwyt sprawia, że następne podejście wraca po gotowy wynik zamiast
     * wgrywać i opłacać dokument powtórnie.
     */
    private DocumentHandle uploadAndRemember(TranslationJob job) {
        byte[] source = objectStore.read(job.getSourceObjectKey());
        DocumentHandle handle = timeProviderCall(() -> provider.uploadDocument(
                source, job.getOriginalFilename(), job.getTargetLang()));

        shortTransaction.execute(status -> repository.saveDocumentHandle(
                job.getId(), handle.documentId(), handle.documentKey()));

        // Bez identyfikatora dokumentu w logu: jest to uchwyt do treści użytkownika u dostawcy.
        log.info("Wgrano dokument do tłumaczenia (id={}, format={})", job.getId(), job.getFileType());
        return handle;
    }

    /**
     * Szuka gotowego wyniku dla tej samej treści, języka i dostawcy u tego samego użytkownika.
     *
     * Wykonuje się we własnej krótkiej transakcji, bo wykonawca nie ma żadnej otaczającej.
     * Zapytanie wyprowadza klucz deduplikacji z samego wiersza zlecenia - uzasadnienie przy
     * findCachedFor.
     */
    private Optional<TranslationCacheHit> findCached(TranslationJob job) {
        List<TranslationCacheHit> hits = shortTransaction.execute(status ->
                repository.findCachedFor(job.getId(), properties.provider(), Limit.of(1)));

        return hits == null || hits.isEmpty() ? Optional.empty() : Optional.of(hits.get(0));
    }

    /**
     * Zapisuje wynik zlecenia i zamawia powiadomienie w jednej transakcji.
     *
     * Wspólna transakcja jest istotą skrzynki nadawczej: zamiar wysłania maila commituje się
     * razem z operacją, którą opisuje, albo nie commituje się wcale. Zamówienie maila poza tą
     * transakcją oznaczałoby, że wycofany zapis wyniku zostawia użytkownikowi wiadomość
     * "tłumaczenie gotowe" prowadzącą do zlecenia, które nadal czeka.
     */
    private void markDone(TranslationJob job, String resultKey, String sourceLang,
                          int billedChars, int charCount) {
        shortTransaction.execute(status -> {
            // Dostawca zapisywany razem z wynikiem, bo dopiero teraz wiadomo, kto wykonał
            // tłumaczenie. Wchodzi do klucza deduplikacji: bez niego wynik atrapy zaspokoiłby
            // zlecenie kierowane do prawdziwego dostawcy.
            repository.markDone(job.getId(), resultKey,
                    sourceLang, properties.provider(), billedChars, charCount, DbClock.now());
            // Dane do powiadomienia pochodzą z osobnego zapytania, a nie z job.getUser(): encja
            // pochodzi z zamkniętej już transakcji rezerwacji, więc leniwe pole user jest tam
            // martwym proxy.
            repository.findCompletedEvent(job.getId()).ifPresent(events::completed);
            return null;
        });
    }

    private void recordFailure(TranslationJob job, int attempt, String code, boolean retryable, Exception cause) {
        String error = trim(code + ": " + cause.getMessage());

        try {
            /*
             * Błąd trwały kończy zlecenie natychmiast, bez czekania na wyczerpanie prób. Na tym
             * polega wartość flagi retryable: nieprawidłowy klucz API albo nieobsługiwany język
             * dadzą tę samą odpowiedź przy każdym podejściu, więc pełny backoff oznaczałby
             * kilkanaście minut zwłoki przed wnioskiem znanym z pierwszej odpowiedzi dostawcy.
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
                // Poziom ERROR, nie WARN: zlecenie użytkownika przepadło i wymaga reakcji człowieka.
                log.error("Zlecenie porzucone po {} podejściach (id={}): {}",
                        attempt, job.getId(), error);
                return;
            }

            Duration delay = properties.retryBackoff().multipliedBy(1L << (attempt - 1));
            // Obcięcie znacznika do precyzji kolumny: wartość jest potem porównywana warunkiem
            // "<= now", więc zaokrąglenie w górę odsuwałoby ponowienie i przy krótkim backoffie
            // powtarzało ten sam wyścig (uzasadnienie przy DbClock).
            Instant nextAttempt = DbClock.truncate(Instant.now().plus(delay));

            shortTransaction.execute(status -> repository.markRetry(job.getId(), nextAttempt, error));
            log.warn("Nie udało się przetłumaczyć zlecenia (id={}, podejście={}/{}), ponowienie za {}: {}",
                    job.getId(), attempt, properties.maxAttempts(), delay, error);

        } catch (Exception e) {
            // Zawiódł zapis wyniku, a nie samo tłumaczenie. Zlecenie zachowuje rezerwację, więc
            // wróci samo po jej upływie - dokładnie ten scenariusz, dla którego rezerwacja jest
            // oknem czasowym, a nie statusem.
            log.error("Nie udało się zapisać wyniku tłumaczenia (id={}): {}", job.getId(), e.toString());
        }
    }

    /**
     * Mierzy czas rozmowy z dostawcą osobno od reszty cyklu.
     *
     * Jest to jedyna metryka rozstrzygająca, czy opóźnienie powstaje po stronie dostawcy, czy
     * kolejki - bez niej oba przypadki wyglądają identycznie. Tag z nazwą dostawcy pozwala
     * porównać atrapę z prawdziwym dostawcą po przełączeniu konfiguracji.
     */
    private <T> T timeProviderCall(java.util.function.Supplier<T> call) {
        return Timer.builder("translation.provider.duration")
                .tag("provider", properties.provider().name().toLowerCase(Locale.ROOT))
                .register(meters)
                .record(call);
    }

    /**
     * Zakłada liczniki, zanim cokolwiek je podbije.
     *
     * Micrometer tworzy licznik dopiero przy pierwszym increment(), więc do pierwszego zdarzenia
     * danego rodzaju /actuator/metrics/&lt;nazwa&gt; odpowiada 404 - odpowiedź nie do odróżnienia
     * od literówki w nazwie, metryki usuniętej przy refaktoryzacji i zepsutej aplikacji. Znaczy
     * "zero", a czyta się jak awaria. Najbardziej dotyczy to translation.chars.translated, bo
     * jest to jedyna metryka mówiąca, ile znaków wydano u dostawcy.
     *
     * translation.cache rejestrowany jest w obu wariantach, ponieważ sam licznik trafień bez
     * pudeł nie daje współczynnika, a pytanie brzmi "czy deduplikacja się opłaca", nie "czy
     * w ogóle działa".
     *
     * translation.jobs.submitted celowo nie jest zakładany: ma tag z językiem docelowym, więc
     * wstępne założenie wszystkich kombinacji byłoby samym szumem.
     */
    private void registerMeters() {
        Counter.builder("translation.chars.translated")
                .description("Znaki faktycznie wysłane do dostawcy - podstawa rachunku")
                .register(meters);
        Counter.builder("translation.chars.saved")
                .description("Znaki, których nie wysłano dzięki deduplikacji")
                .register(meters);
        Counter.builder("translation.cache").tag("result", "hit").register(meters);
        Counter.builder("translation.cache").tag("result", "miss").register(meters);
    }

    /**
     * Zlicza zakończenia zleceń z tagiem wyniku. Tag zamiast trzech osobnych liczników sprawia,
     * że "ile zleceń łącznie" i "jaki odsetek padł" to jedno zapytanie w Prometheusie.
     */
    private void countOutcome(String outcome) {
        meters.counter("translation.jobs.finished", "outcome", outcome).increment();
    }

    /**
     * Zlicza skuteczność deduplikacji. Tag zamiast dwóch liczników, ponieważ interesujący jest
     * iloraz - sam licznik trafień nie mówi, czy jest to dużo.
     */
    private void countCache(String result) {
        meters.counter("translation.cache", "result", result).increment();
    }

    /** Kolumna last_error mieści 500 znaków - dłuższy komunikat przerwałby zapis wyniku. */
    private String trim(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 500 ? value : value.substring(0, 500);
    }

    /**
     * Pozwala trwającym tłumaczeniom dokończyć się przy zamykaniu aplikacji.
     *
     * Przerwanie ich w locie oznaczałoby znaki zużyte u dostawcy bez zapisanego wyniku, czyli
     * powtórne tłumaczenie po restarcie - dopuszczalne, ale niepotrzebne, skoro wystarczy
     * odczekać do zakończenia bieżącej paczki.
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
