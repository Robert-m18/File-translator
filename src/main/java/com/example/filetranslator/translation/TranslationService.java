/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.filetranslator.translation;

import com.example.filetranslator.common.time.DbClock;
import com.example.filetranslator.translation.dto.TranslationContent;
import com.example.filetranslator.translation.dto.TranslationJobResponse;
import com.example.filetranslator.translation.dto.TranslationResultView;
import com.example.filetranslator.translation.exception.TranslationJobNotFoundException;
import com.example.filetranslator.translation.exception.TranslationNotReadyException;
import com.example.filetranslator.translation.exception.TranslationQuotaExceededException;
import com.example.filetranslator.translation.model.TargetLanguage;
import com.example.filetranslator.translation.model.TranslationJob;
import com.example.filetranslator.translation.model.TranslationStatus;
import com.example.filetranslator.translation.repository.TranslationJobRepository;
import com.example.filetranslator.translation.storage.ObjectKeys;
import com.example.filetranslator.translation.storage.ObjectStore;
import com.example.filetranslator.user.model.User;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
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
public class TranslationService {

    private static final Duration QUOTA_WINDOW = Duration.ofDays(1);

    private final TranslationJobRepository repository;
    private final TranslationProperties properties;
    private final ObjectStore objectStore;
    private final MeterRegistry meters;

    /**
     * Transakcja wołana programowo, bo metody publiczne tej klasy NIE są transakcyjne -
     * rozmowa z magazynem obiektowym musi zostać poza transakcją, żeby nie trzymać
     * połączenia z puli przez czas przesyłania pliku. Uzasadnienie przy submit.
     */
    private final TransactionTemplate transaction;

    public TranslationService(TranslationJobRepository repository,
                              TranslationProperties properties,
                              ObjectStore objectStore,
                              MeterRegistry meters,
                              PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.properties = properties;
        this.objectStore = objectStore;
        this.meters = meters;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /**
     * Przyjmuje zlecenie i oddaje jego stan początkowy.
     *
     * CELOWO BEZ @Transactional NA METODZIE, w odróżnieniu od poprzedniej wersji. Wgranie
     * pliku do magazynu obiektowego to rozmowa przez sieć, a transakcja obejmująca ją
     * trzymałaby połączenie z puli przez cały ten czas - dokładnie ten sam błąd, którego
     * unika AuthService.login (BCrypt) i OutboxPublisher (rozmowa z SMTP). Przy dużym pliku
     * i wolnym magazynie kilka równoczesnych zleceń wysuszyłoby pulę na czekaniu na sieć.
     *
     * Transakcja obejmuje więc wyłącznie to, co musi być atomowe: sprawdzenie limitu i zapis
     * wiersza. Przez TransactionTemplate, a nie przez @Transactional na metodzie prywatnej -
     * wywołanie własnej metody omija proxy Springa, ta sama pułapka co w RefreshTokenService.
     *
     * KOLEJNOŚĆ: NAJPIERW OBIEKT, POTEM WIERSZ. Postgres i magazyn obiektowy nie mają
     * wspólnej transakcji, więc jedno z dwojga może się nie udać - i cała decyzja polega
     * na wybraniu, KTÓRA połówka ma zostać. Zostawiamy obiekt bez wiersza: to zajęte miejsce,
     * które sprząta reguła wygasania na kubełku, i nikt tego nie zauważy. Odwrotnie
     * zostawiałoby wiersz bez pliku, czyli zlecenie widoczne na liście, którego nie da się
     * ani przetłumaczyć, ani pobrać, i które trzeba by wykrywać osobnym mechanizmem.
     */
    public TranslationJobResponse submit(User owner, UploadedFile file, TargetLanguage targetLang) {
        String contentHash = file.contentHash();

        // Klucz powstaje PRZED wierszem, więc jego segmentem jest UUID, a nie id zlecenia -
        // uzasadnienie w ObjectKeys. Rozszerzenie bierze się z ROZPOZNANEGO typu, nie z nazwy
        // przysłanej przez klienta.
        String jobPrefix = ObjectKeys.jobPrefix(owner.getId(), ObjectKeys.newStorageId());
        String sourceKey = ObjectKeys.sourceKey(jobPrefix, file.type().extension());

        objectStore.put(sourceKey, file.content(), file.type().contentType());

        return transaction.execute(status -> insertJob(owner, file, targetLang, contentHash, sourceKey));
    }

    private TranslationJobResponse insertJob(User owner,
                                             UploadedFile file,
                                             TargetLanguage targetLang,
                                             String contentHash,
                                             String sourceKey) {
        Instant now = DbClock.now();

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
         * i odpowiada wyłącznie "czy jest". Kopiowanie wyniku dzieje się później, w workerze.
         *
         * Odrzucenie na limicie zostawia wgrany przed chwilą obiekt bez wiersza. To jest
         * przyjęta cena kolejności opisanej wyżej - osierocony plik wygasa sam.
         */
        boolean expectedCacheHit =
                repository.existsCached(owner.getId(), contentHash, targetLang, properties.provider());

        /*
         * Dla DOKUMENTU charCount() daje 0, więc sprawdzenie limitu sprowadza się do pytania
         * "czy budżet jest już wyczerpany", a nie "czy zmieści się ten plik" - liczby znaków
         * w PDF-ie nie da się poznać przed wysłaniem go do dostawcy. Konsekwencja przyjęta
         * świadomie: jeden dokument może przekroczyć dobowy limit, bo naliczy się dopiero
         * po fakcie (billedChars z odpowiedzi dostawcy). Szkodę ogranicza limit rozmiaru
         * per format - uzasadnienie w FileType.
         */
        if (!expectedCacheHit) {
            enforceDailyQuota(owner, file.charCount(), now);
        }

        /*
         * Ta sama odpowiedź decyduje o DWÓCH rzeczach i musi być jedna: czy naliczyć limit
         * teraz i ile znaków ten wiersz wnosi do limitu NASTĘPNYCH zleceń (billedChars).
         * Rozdzielenie ich na dwa zapytania byłoby tym samym rozjazdem, przed którym ostrzega
         * komentarz przy findCachedFor - z tą różnicą, że tu rozjazd trwałby w danych.
         */
        TranslationJob job = repository.save(new TranslationJob(
                owner, file.filename(), targetLang, file.type(), sourceKey, contentHash,
                file.charCount(), now, expectedCacheHit));

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
        long used = repository.sumBilledCharsSince(owner.getId(), now.minus(QUOTA_WINDOW));
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
     * Otwiera wynik do pobrania: strumień z magazynu plus dane do nagłówków.
     *
     * Rozróżnia CZTERY sytuacje, i to rozróżnienie jest całą wartością tej metody: nie ma
     * takiego zlecenia (404), jest, ale nie jest gotowe (409 ze statusem, żeby front wiedział,
     * czy dalej odpytywać), jest gotowe, ale pliku już nie ma w magazynie (410), oraz jest
     * i da się pobrać.
     *
     * Czwarty przypadek pojawił się razem z magazynem obiektowym i nie jest teoretyczny:
     * wiersze kasuje retencja aplikacji, a obiekty - reguła wygasania na kubełku. Te dwie
     * wartości nie są niczym związane poza uważnością, więc ich rozjazd MUSI mieć objaw
     * inny niż 500. Patrz ObjectMissingException.
     *
     * Strumień otwieramy POZA transakcją: pobieranie pliku trwa tyle, ile trwa łącze klienta,
     * a połączenie z bazą nie ma prawa być przez ten czas zajęte.
     */
    public TranslationContent openOwnResult(User owner, Long jobId) {
        TranslationResultView view = readResultView(owner, jobId);
        return new TranslationContent(
                objectStore.open(view.resultObjectKey()),
                view.originalFilename(),
                view.targetLang(),
                view.fileType());
    }

    /**
     * Bez @Transactional i to jest świadome: adnotacja na metodzie wołanej z tej samej klasy
     * omija proxy Springa, więc nie robiłaby NIC - a wyglądałaby, jakby coś robiła. To jedno
     * zapytanie, które i tak leci we własnej transakcji repozytorium.
     */
    private TranslationResultView readResultView(User owner, Long jobId) {
        TranslationResultView view = repository.findResult(jobId, owner.getId())
                .orElseThrow(TranslationJobNotFoundException::new);

        if (view.status() != TranslationStatus.DONE || view.resultObjectKey() == null) {
            throw new TranslationNotReadyException(view.status());
        }
        return view;
    }

    /**
     * Kasuje zlecenie razem z jego plikami.
     *
     * KOLEJNOŚĆ JEST ODWROTNA NIŻ PRZY TWORZENIU i to nie jest niekonsekwencja - to ten sam
     * niezmiennik widziany z drugiej strony. Przy tworzeniu: najpierw obiekt, potem wiersz.
     * Przy kasowaniu: najpierw wiersz, potem obiekt. W obu przypadkach stanem pośrednim,
     * który może zostać po awarii, jest OBIEKT BEZ WIERSZA - nigdy wiersz bez obiektu.
     * Pierwsze to zajęte miejsce, które wygasa samo; drugie to zlecenie, którego nie da się
     * ani przetłumaczyć, ani pobrać.
     *
     * Klucz odczytujemy PRZED skasowaniem wiersza, bo potem nie ma już skąd wziąć prefiksu.
     */
    public void deleteOwn(User owner, Long jobId) {
        String sourceKey = repository.findSourceKey(jobId, owner.getId())
                .orElseThrow(TranslationJobNotFoundException::new);

        Integer deleted = transaction.execute(status -> repository.deleteOwned(jobId, owner.getId()));
        if (deleted == null || deleted == 0) {
            // Zniknęło między odczytem klucza a kasowaniem - z punktu widzenia wołającego
            // to ten sam przypadek co "nie ma takiego zlecenia".
            throw new TranslationJobNotFoundException();
        }

        // Po zatwierdzeniu, a nie w transakcji: gdyby kasowanie wiersza wycofało się po
        // usunięciu plików, zostałoby zlecenie bez treści - czyli dokładnie ten stan,
        // którego cały ten niezmiennik zabrania.
        objectStore.deletePrefix(ObjectKeys.prefixOf(sourceKey));

        log.info("Usunięto zlecenie tłumaczenia razem z plikami (id={})", jobId);
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
