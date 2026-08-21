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
 * Przyjmowanie zleceń tłumaczenia oraz odczyty i kasowanie dla ich właściciela.
 *
 * Samo tłumaczenie wykonuje TranslationJobWorker, poza żądaniem HTTP. Ta klasa zapisuje
 * zlecenie i oddaje sterowanie, dzięki czemu czas odpowiedzi API nie zależy od dostawcy
 * tłumaczenia - kolejka jest granicą między tym, co widzi użytkownik, a rozmową z zewnętrznym
 * systemem.
 *
 * Wszystkie odczyty przekazują identyfikator właściciela do zapytania, zamiast pobierać wiersz
 * i porównywać właściciela w kodzie. Dzięki temu cudze zlecenie jest z zewnątrz nieodróżnialne
 * od nieistniejącego.
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
     * Transakcja wołana programowo, ponieważ metody publiczne tej klasy nie są transakcyjne:
     * rozmowa z magazynem obiektowym musi pozostać poza transakcją, żeby nie zajmować
     * połączenia z puli przez czas przesyłania pliku.
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
     * Przyjmuje zlecenie: zapisuje plik w magazynie, tworzy wiersz kolejki i zwraca stan początkowy.
     *
     * Metoda celowo nie jest transakcyjna w całości. Wgranie pliku to rozmowa przez sieć,
     * a transakcja obejmująca ją trzymałaby połączenie z puli przez cały czas przesyłania -
     * przy większych plikach kilka równoczesnych zleceń wyczerpałoby pulę na oczekiwaniu na
     * sieć. Transakcja obejmuje więc wyłącznie to, co musi być atomowe: sprawdzenie limitu
     * i zapis wiersza. Realizuje ją TransactionTemplate, a nie adnotacja na metodzie prywatnej,
     * ponieważ wywołanie własnej metody omija proxy Springa.
     *
     * Kolejność operacji - najpierw obiekt, potem wiersz - wynika z tego, że baza i magazyn
     * obiektowy nie mają wspólnej transakcji, więc trzeba wybrać, która połówka może zostać po
     * awarii. Zostaje obiekt bez wiersza: to zajęte miejsce, które sprząta reguła wygasania na
     * kubełku. Odwrotna kolejność zostawiałaby wiersz bez pliku, czyli zlecenie widoczne na
     * liście, którego nie da się ani przetłumaczyć, ani pobrać, i które wymagałoby osobnego
     * mechanizmu wykrywania.
     */
    public TranslationJobResponse submit(User owner, UploadedFile file, TargetLanguage targetLang) {
        String contentHash = file.contentHash();

        // Klucz powstaje przed wierszem, więc jego segmentem jest UUID, a nie identyfikator
        // zlecenia. Rozszerzenie pochodzi z rozpoznanego typu pliku, nie z nazwy przysłanej
        // przez klienta.
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
         * Dobowy limit znaków chroni konto u dostawcy, a zlecenie zaspokojone z cache'a nie
         * kosztuje tam ani znaku - naliczanie go byłoby karą za operację, która nic nie kosztuje.
         *
         * Pominięcie limitu nie otwiera dziury: ten sam plik wysyłany w pętli ogranicza limiter
         * żądań, który ma na tę ścieżkę osobną politykę, więc sufit takiej pętli wyznacza liczba
         * żądań na godzinę.
         *
         * Samo sprawdzenie jest tanie - idzie po indeksie i odpowiada wyłącznie na pytanie, czy
         * gotowy wynik istnieje. Kopiowanie wyniku wykonuje później wykonawca kolejki.
         *
         * Odrzucenie na limicie zostawia wgrany przed chwilą obiekt bez wiersza; jest to przyjęta
         * cena kolejności opisanej wyżej, a osierocony plik wygasa samoczynnie.
         */
        boolean expectedCacheHit =
                repository.existsCached(owner.getId(), contentHash, targetLang, properties.provider());

        /*
         * Dla dokumentu liczba znaków wynosi zero, więc sprawdzenie limitu sprowadza się do
         * pytania, czy budżet jest już wyczerpany, a nie czy zmieści się ten plik - liczby znaków
         * w dokumencie nie da się poznać przed wysłaniem go do dostawcy. Konsekwencją przyjętą
         * świadomie jest to, że jeden dokument może przekroczyć dobowy limit, ponieważ naliczy się
         * dopiero po fakcie. Szkodę ogranicza limit rozmiaru pliku ustalony osobno dla każdego
         * formatu.
         */
        if (!expectedCacheHit) {
            enforceDailyQuota(owner, file.charCount(), now);
        }

        /*
         * Wynik sprawdzenia cache'a decyduje o dwóch rzeczach naraz i dlatego pochodzi z jednego
         * zapytania: czy naliczyć limit teraz oraz ile znaków ten wiersz wniesie do limitu
         * kolejnych zleceń. Rozdzielenie ich na dwa zapytania groziłoby rozjazdem utrwalonym
         * w danych.
         */
        TranslationJob job = repository.save(new TranslationJob(
                owner, file.filename(), targetLang, file.type(), sourceKey, contentHash,
                file.charCount(), now, expectedCacheHit));

        // Zestawiony z licznikiem zakończeń daje długość kolejki bez odpytywania bazy: rozjazd
        // między tymi wartościami to zlecenia, które utknęły.
        meters.counter("translation.jobs.submitted", "target", targetLang.name()).increment();

        // W logu nie ma nazwy pliku ani adresu - to dane użytkownika. Powiązanie z żądaniem daje
        // identyfikator żądania, a identyfikator zlecenia jest uchwytem po stronie wykonawcy,
        // który pracuje na wątku bez kontekstu żądania.
        log.info("Przyjęto zlecenie tłumaczenia (id={}, znaków={}, język={})",
                job.getId(), job.getCharCount(), targetLang);

        return toResponse(job);
    }

    /** Sprawdza dobowy budżet znaków wydanych u dostawcy i odrzuca zlecenie, gdy się nie mieści. */
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
     * Otwiera wynik do pobrania: strumień z magazynu wraz z danymi do nagłówków odpowiedzi.
     *
     * Metoda rozróżnia cztery sytuacje i na tym polega jej wartość: zlecenie nie istnieje lub
     * należy do kogoś innego, istnieje ale nie jest gotowe (odpowiedź niesie status, żeby
     * frontend wiedział, czy odpytywać dalej), jest gotowe ale pliku nie ma już w magazynie,
     * oraz jest gotowe i da się je pobrać.
     *
     * Trzeci przypadek nie jest teoretyczny: wiersze kasuje retencja aplikacji, a obiekty reguła
     * wygasania na kubełku, więc rozjazd tych dwóch wartości musi mieć objaw inny niż błąd 500.
     *
     * Strumień otwierany jest poza transakcją, ponieważ pobieranie pliku trwa tyle, ile łącze
     * klienta, a połączenie z bazą nie może być przez ten czas zajęte.
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
     * Odczytuje wskazanie na wynik i sprawdza jego gotowość.
     *
     * Metoda nie ma adnotacji transakcyjnej, ponieważ wołana jest z tej samej klasy, a wtedy
     * adnotacja nie zadziałałaby wcale - wyglądając, jakby działała. Jest to jedno zapytanie,
     * które i tak wykonuje się we własnej transakcji repozytorium.
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
     * Kolejność jest odwrotna niż przy tworzeniu i wynika z tego samego niezmiennika widzianego
     * z drugiej strony: przy tworzeniu najpierw obiekt, potem wiersz; przy kasowaniu najpierw
     * wiersz, potem obiekt. W obu przypadkach stanem pośrednim po awarii jest obiekt bez wiersza,
     * czyli zajęte miejsce, które wygasa samoczynnie, a nigdy wiersz bez obiektu, czyli zlecenie
     * niemożliwe ani do przetłumaczenia, ani do pobrania.
     *
     * Klucz odczytywany jest przed skasowaniem wiersza, bo potem nie ma już skąd wziąć prefiksu.
     */
    public void deleteOwn(User owner, Long jobId) {
        String sourceKey = repository.findSourceKey(jobId, owner.getId())
                .orElseThrow(TranslationJobNotFoundException::new);

        Integer deleted = transaction.execute(status -> repository.deleteOwned(jobId, owner.getId()));
        if (deleted == null || deleted == 0) {
            // Wiersz zniknął między odczytem klucza a kasowaniem - dla wołającego jest to ten sam
            // przypadek co brak zlecenia.
            throw new TranslationJobNotFoundException();
        }

        // Po zatwierdzeniu, a nie w transakcji: wycofanie kasowania wiersza po usunięciu plików
        // zostawiłoby zlecenie bez treści, czyli stan, którego ten niezmiennik zabrania.
        objectStore.deletePrefix(ObjectKeys.prefixOf(sourceKey));

        log.info("Usunięto zlecenie tłumaczenia razem z plikami (id={})", jobId);
    }

    /**
     * Kasuje wszystkie pliki użytkownika. Wołane przy usuwaniu konta z panelu administracyjnego.
     *
     * Metoda kasuje pliki, a nie wiersze, i nazwa mówi dokładnie tyle, ile ona robi. Wiersze
     * zleceń znikają razem z wierszem konta dzięki kaskadzie klucza obcego, czyli w jednej
     * transakcji z usunięciem konta. Powtórzenie tego kasowania tutaj byłoby zapytaniem zawsze
     * trafiającym w zero wierszy, a wyglądałoby na jedyną ścieżkę usuwania.
     *
     * Magazyn obiektowy nie uczestniczy w tamtej transakcji i to jest jedyny powód istnienia tej
     * metody: bajty trzeba usunąć osobnym wywołaniem po sieci.
     *
     * Wołać po zatwierdzeniu usunięcia konta, nigdy przed - obowiązuje ten sam niezmiennik co
     * przy kasowaniu pojedynczego zlecenia. Odwrotna kolejność zostawiłaby po nieudanym usunięciu
     * konto z widocznymi zleceniami, których treści już nie ma.
     */
    public void deleteAllFilesOf(Long userId) {
        objectStore.deletePrefix(ObjectKeys.userPrefix(userId));
        log.info("Usunięto pliki użytkownika z magazynu (userId={})", userId);
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
