/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.filetranslator.translation.repository;

import com.example.filetranslator.translation.TranslationCompletedEvent;
import com.example.filetranslator.translation.TranslationProperties;
import com.example.filetranslator.translation.dto.TranslationCacheHit;
import com.example.filetranslator.translation.dto.TranslationJobResponse;
import com.example.filetranslator.translation.dto.TranslationResultView;
import com.example.filetranslator.translation.model.TargetLanguage;
import com.example.filetranslator.translation.model.TranslationJob;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Dostęp do zleceń tłumaczenia: kolejka dla wykonawcy, odczyty dla API i zapytania retencyjne.
 *
 * W całej klasie obowiązują dwie zasady:
 *
 * 1. Każdy odczyt wykonywany dla użytkownika ma userId w warunku WHERE, zamiast pobierać wiersz
 *    po identyfikatorze i porównywać właściciela w kodzie. Dzięki temu cudze zlecenie jest
 *    nieodróżnialne od nieistniejącego i API nie pozwala sprawdzać, które identyfikatory istnieją.
 *
 * 2. Odczyty prezentacyjne korzystają z projekcji, nie z encji. Encja zlecenia niesie komplet
 *    kolumn technicznych, a projekcja czyta wyłącznie to, co faktycznie trafia do odpowiedzi.
 */
@Repository
public interface TranslationJobRepository extends JpaRepository<TranslationJob, Long> {

    /**
     * Zwraca zlecenia gotowe do wykonania i od razu blokuje je dla tej instancji.
     *
     * Klauzula FOR UPDATE SKIP LOCKED (tak Hibernate tłumaczy PESSIMISTIC_WRITE z limitem
     * blokady -2) sprawia, że druga instancja czytająca w tym samym momencie pomija zajęte
     * wiersze zamiast czekać lub odczytać ten sam komplet. Dwie instancje pobierają więc
     * rozłączne paczki zleceń.
     *
     * Status PROCESSING występuje w warunku razem z PENDING celowo: o tym, czy wolno pobrać
     * wiersz, decyduje wyłącznie next_attempt_at. Dzięki temu zlecenie porzucone przez proces,
     * który padł w trakcie rozmowy z dostawcą, wraca do obiegu po upływie okna rezerwacji,
     * zamiast pozostać w PROCESSING na zawsze.
     *
     * Blokada trwa tylko przez transakcję rezerwacji - rozmowa z dostawcą toczy się po jej
     * zwolnieniu, bo blokada bazodanowa nigdy nie obejmuje operacji zewnętrznej.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2")) // org.hibernate.Timeouts.SKIP_LOCKED
    @Query("""
            select j from TranslationJob j
            where j.status in (com.example.filetranslator.translation.model.TranslationStatus.PENDING,
                               com.example.filetranslator.translation.model.TranslationStatus.PROCESSING)
              and j.nextAttemptAt <= :now
            order by j.id
            """)
    List<TranslationJob> findClaimable(@Param("now") Instant now, Limit limit);

    /**
     * Rezerwuje odczytane zlecenia: przesuwa next_attempt_at w przyszłość, podbija licznik
     * podejść i ustawia status PROCESSING.
     *
     * Musi wykonać się w tej samej transakcji co findClaimable, ponieważ to blokada z odczytu
     * gwarantuje, że między odczytem a rezerwacją nikt nie wszedł. Powtórzony warunek na status
     * i next_attempt_at kosztuje tyle co nic, a chroni przed zarezerwowaniem wiersza, który
     * zmienił stan mimo blokady.
     *
     * @return liczba faktycznie zarezerwowanych wierszy
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update TranslationJob j
            set j.attempts = j.attempts + 1,
                j.status = com.example.filetranslator.translation.model.TranslationStatus.PROCESSING,
                j.nextAttemptAt = :reservedUntil
            where j.id in :ids
              and j.status in (com.example.filetranslator.translation.model.TranslationStatus.PENDING,
                               com.example.filetranslator.translation.model.TranslationStatus.PROCESSING)
              and j.nextAttemptAt <= :now
            """)
    int claim(@Param("ids") Collection<Long> ids,
              @Param("now") Instant now,
              @Param("reservedUntil") Instant reservedUntil);

    /**
     * Zapisuje wynik zlecenia i kończy je statusem DONE.
     *
     * Liczba rozliczonych znaków zapisywana jest razem z wynikiem, ponieważ dopiero tutaj
     * wiadomo, czy dostawca był wołany. Wartość ustawiona przy przyjęciu zlecenia jest jedynie
     * przewidywaniem: gotowy wiersz mógł w międzyczasie zostać skasowany przez użytkownika albo
     * usunięty przez retencję, a wtedy zlecenie przewidziane jako darmowe jednak trafiło do
     * dostawcy i musi zostać naliczone.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update TranslationJob j
            set j.status = com.example.filetranslator.translation.model.TranslationStatus.DONE,
                j.resultObjectKey = :resultObjectKey,
                j.sourceLang = :sourceLang,
                j.provider = :provider,
                j.billedChars = :billedChars,
                j.charCount = :charCount,
                j.completedAt = :now,
                j.providerDocumentId = null,
                j.providerDocumentKey = null,
                j.lastError = null
            where j.id = :id
            """)
    int markDone(@Param("id") Long id,
                 @Param("resultObjectKey") String resultObjectKey,
                 @Param("sourceLang") String sourceLang,
                 @Param("provider") TranslationProperties.Provider provider,
                 @Param("billedChars") int billedChars,
                 @Param("charCount") int charCount,
                 @Param("now") Instant now);

    /**
     * Zapamiętuje uchwyt do dokumentu wgranego u dostawcy.
     *
     * Wołający zatwierdza tę zmianę we własnej, natychmiastowej transakcji, ponieważ od chwili
     * wgrania dokument jest już opłacony. Zapis dopiero razem z wynikiem oznaczałby, że proces
     * przerwany w trakcie tłumaczenia traci opłacony dokument i wgrywa go od nowa.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update TranslationJob j
            set j.providerDocumentId = :documentId, j.providerDocumentKey = :documentKey
            where j.id = :id
            """)
    int saveDocumentHandle(@Param("id") Long id,
                           @Param("documentId") String documentId,
                           @Param("documentKey") String documentKey);

    /** Czyści uchwyt, gdy dokumentu nie ma już u dostawcy - kolejne podejście wgra go od nowa. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update TranslationJob j
            set j.providerDocumentId = null, j.providerDocumentKey = null
            where j.id = :id
            """)
    int clearDocumentHandle(@Param("id") Long id);

    /**
     * Odkłada zlecenie do następnego odpytania dostawcy, nie licząc tego jako podejścia.
     *
     * Odjęcie licznika przywraca kolumnie jej znaczenie. Rezerwacja podbija licznik przy każdym
     * pobraniu zlecenia, bo w trybie tekstowym pobranie jest równoznaczne z próbą tłumaczenia.
     * Przy dokumencie pobranie bywa wyłącznie sprawdzeniem gotowości, a dostawca może tłumaczyć
     * dłużej niż dopuszczalna liczba podejść. Bez odjęcia dokument tłumaczony kilka minut
     * zostałby porzucony jako nieudany, a kolumna zaczęłaby znaczyć "liczba zajrzeń" zamiast
     * "liczba nieudanych prób".
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update TranslationJob j
            set j.attempts = j.attempts - 1, j.nextAttemptAt = :nextAttemptAt
            where j.id = :id
            """)
    int markPolling(@Param("id") Long id, @Param("nextAttemptAt") Instant nextAttemptAt);

    /** Odnotowuje porażkę z zamiarem ponowienia - wiersz wraca do PENDING z odsuniętym terminem. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update TranslationJob j
            set j.status = com.example.filetranslator.translation.model.TranslationStatus.PENDING,
                j.nextAttemptAt = :nextAttemptAt,
                j.lastError = :error
            where j.id = :id
            """)
    int markRetry(@Param("id") Long id,
                  @Param("nextAttemptAt") Instant nextAttemptAt,
                  @Param("error") String error);

    /** Kończy zlecenie niepowodzeniem - bez dalszych podejść. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update TranslationJob j
            set j.status = com.example.filetranslator.translation.model.TranslationStatus.FAILED,
                j.completedAt = :now,
                j.lastError = :error
            where j.id = :id
            """)
    int markFailed(@Param("id") Long id,
                   @Param("error") String error,
                   @Param("now") Instant now);

    /** Stan pojedynczego zlecenia właściciela - do odpytywania o postęp. */
    @Query("""
            select new com.example.filetranslator.translation.dto.TranslationJobResponse(
                j.id, j.originalFilename, j.sourceLang, j.targetLang, j.status,
                j.charCount, j.createdAt, j.completedAt)
            from TranslationJob j
            where j.id = :id and j.user.id = :userId
            """)
    Optional<TranslationJobResponse> findSummary(@Param("id") Long id, @Param("userId") Long userId);

    /**
     * Lista zleceń właściciela, najnowsze pierwsze.
     *
     * Zapytanie zliczające podane jest jawnie, ponieważ przy wyrażeniu konstruktora automatyczne
     * przepisanie zapytania na count bywa zawodne.
     */
    @Query(value = """
            select new com.example.filetranslator.translation.dto.TranslationJobResponse(
                j.id, j.originalFilename, j.sourceLang, j.targetLang, j.status,
                j.charCount, j.createdAt, j.completedAt)
            from TranslationJob j
            where j.user.id = :userId
            order by j.id desc
            """,
            countQuery = "select count(j) from TranslationJob j where j.user.id = :userId")
    Page<TranslationJobResponse> findSummaries(@Param("userId") Long userId, Pageable pageable);

    /**
     * Zbiera jednym zapytaniem dane potrzebne do powiadomienia właściciela o gotowym tłumaczeniu.
     *
     * Projekcja zastępuje odczyt przez job.getUser(), ponieważ encja, którą dysponuje wykonawca,
     * pochodzi z zakończonej już transakcji rezerwacji, a pole user jest leniwym proxy - odczyt
     * adresu poza tamtą sesją kończy się wyjątkiem, i to na ścieżce wykonywanej dopiero po
     * udanym tłumaczeniu. Ponowne pobranie całej encji byłoby droższe bez żadnego zysku:
     * projekcja czyta wyłącznie kolumny, które trafiają do powiadomienia.
     */
    @Query("""
            select new com.example.filetranslator.translation.TranslationCompletedEvent(
                j.id, u.id, u.email, u.name, j.originalFilename, j.targetLang, j.charCount)
            from TranslationJob j join j.user u
            where j.id = :id
            """)
    Optional<TranslationCompletedEvent> findCompletedEvent(@Param("id") Long id);

    /** Wskazanie na wynik: klucz obiektu wraz z danymi potrzebnymi do nagłówka pobierania. */
    @Query("""
            select new com.example.filetranslator.translation.dto.TranslationResultView(
                j.status, j.originalFilename, j.targetLang, j.fileType, j.resultObjectKey)
            from TranslationJob j
            where j.id = :id and j.user.id = :userId
            """)
    Optional<TranslationResultView> findResult(@Param("id") Long id, @Param("userId") Long userId);

    /**
     * Klucz pliku źródłowego własnego zlecenia - służy do wyliczenia prefiksu przy kasowaniu.
     *
     * Warunek na userId jest tu równie istotny co przy odczytach: bez niego kasowanie dałoby się
     * skierować na cudzy prefiks, czyli obejść ochronę per wiersz jednym parametrem w adresie.
     */
    @Query("select j.sourceObjectKey from TranslationJob j where j.id = :id and j.user.id = :userId")
    Optional<String> findSourceKey(@Param("id") Long id, @Param("userId") Long userId);

    /**
     * Klucze plików źródłowych zleceń starszych niż podany moment - dla zadania retencyjnego.
     *
     * Odczyt musi poprzedzać kasowanie wierszy, ponieważ po nim nie ma już skąd wziąć prefiksów.
     * Reguła wygasania na kubełku jest siatką bezpieczeństwa, ale nie zastępuje tego kroku:
     * po skróceniu retencji aplikacji wiersze zniknęłyby wcześniej niż pliki, a plików nie
     * miałby już kto wskazać.
     */
    @Query("select j.sourceObjectKey from TranslationJob j where j.createdAt < :cutoff")
    List<String> findSourceKeysCreatedBefore(@Param("cutoff") Instant cutoff);

    /**
     * Liczba znaków faktycznie wydanych u dostawcy od podanego momentu - podstawa dobowego limitu.
     *
     * Sumuje billedChars, a nie charCount: zlecenie zaspokojone z cache'a ma tu zero. Sumowanie
     * charCount sprawiałoby, że powtórzony plik nie płaci za siebie, ale podnosi licznik
     * następnym zleceniom, przez co nowy plik odbijałby się od limitu na budżecie, którego nikt
     * nie wydał.
     *
     * coalesce jest konieczne, bo suma z pustego zbioru to NULL - bez niego pierwsze zlecenie
     * nowego użytkownika kończyłoby się wyjątkiem.
     */
    @Query("""
            select coalesce(sum(j.billedChars), 0) from TranslationJob j
            where j.user.id = :userId and j.createdAt >= :since
            """)
    long sumBilledCharsSince(@Param("userId") Long userId, @Param("since") Instant since);

    /**
     * Sprawdza sam fakt istnienia gotowego tłumaczenia tej treści, bez pobierania wyniku.
     *
     * Zapytanie wykonuje się na ścieżce żądania HTTP - decyduje o tym, czy naliczyć dobowy limit
     * znaków - więc świadomie nie dotyka treści. Kopiowanie wyniku realizuje później
     * findCachedFor, już poza żądaniem.
     *
     * Kluczem jest użytkownik, odcisk treści, język docelowy oraz dostawca. Ten ostatni jest
     * niezbędny: bez niego wynik atrapy zaspokoiłby zlecenie kierowane do prawdziwego dostawcy,
     * co jest jedynym przypadkiem, w którym deduplikacja zwraca wynik błędny, a nie tylko szybki.
     */
    @Query("""
            select count(j) > 0 from TranslationJob j
            where j.user.id = :userId
              and j.contentHash = :contentHash
              and j.targetLang = :targetLang
              and j.provider = :provider
              and j.status = com.example.filetranslator.translation.model.TranslationStatus.DONE
              and j.resultObjectKey is not null
            """)
    boolean existsCached(@Param("userId") Long userId,
                         @Param("contentHash") String contentHash,
                         @Param("targetLang") TargetLanguage targetLang,
                         @Param("provider") TranslationProperties.Provider provider);

    /**
     * Zwraca gotowy wynik nadający się do przepisania na zlecenie o podanym identyfikatorze.
     *
     * Klucz deduplikacji wyprowadzany jest z samego wiersza zlecenia (złączenie po user_id,
     * content_hash i target_lang), a nie z parametrów podanych przez wołającego. Powód jest ten
     * sam co przy findCompletedEvent: encja, którą trzyma wykonawca, pochodzi z zamkniętej
     * transakcji rezerwacji, więc odczyt właściciela z leniwego proxy działałby wyłącznie
     * dzięki szczegółowi implementacyjnemu Hibernate'a.
     *
     * Warunki na status, dostawcę i treść muszą pozostać identyczne jak w existsCached. Rozjazd
     * między nimi oznaczałby pominięcie dobowego limitu na podstawie trafienia, do którego potem
     * nie dochodzi - czyli zlecenie przyjęte jako darmowe trafiłoby jednak do dostawcy.
     *
     * Warunek na różne identyfikatory jest asekuracją: zlecenie w trakcie ma status PROCESSING,
     * więc samo siebie nie zaspokoi, ale ponowne przetworzenie wiersza zakończonego nie może
     * zamienić się w kopiowanie wyniku z samego siebie.
     *
     * Wiersze bez odcisku treści nigdy nie trafiają, bo NULL nie równa się niczemu - nie trzeba
     * ich wykluczać osobnym warunkiem.
     *
     * Typ List wraz z parametrem Limit wynika z tego, że JPQL nie ma odpowiednika "pierwszy wiersz".
     */
    @Query("""
            select new com.example.filetranslator.translation.dto.TranslationCacheHit(
                done.resultObjectKey, done.sourceLang, done.charCount)
            from TranslationJob job
            join TranslationJob done
              on done.user.id = job.user.id
             and done.contentHash = job.contentHash
             and done.targetLang = job.targetLang
            where job.id = :jobId
              and done.id <> job.id
              and done.provider = :provider
              and done.status = com.example.filetranslator.translation.model.TranslationStatus.DONE
              and done.resultObjectKey is not null
            order by done.id desc
            """)
    List<TranslationCacheHit> findCachedFor(@Param("jobId") Long jobId,
                                            @Param("provider") TranslationProperties.Provider provider,
                                            Limit limit);

    /**
     * Kasuje własne zlecenie użytkownika - warunek na userId uniemożliwia skasowanie cudzego.
     *
     * Usuwa wyłącznie wiersz. Pliki kasuje TranslationService po zatwierdzeniu tej transakcji;
     * kolejność jest odwrotna niż przy tworzeniu, dzięki czemu stanem pośrednim po awarii jest
     * plik bez wiersza, a nie zlecenie bez treści.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from TranslationJob j where j.id = :id and j.user.id = :userId")
    int deleteOwned(@Param("id") Long id, @Param("userId") Long userId);

    /** Retencja: kasuje zlecenia starsze niż podany moment, niezależnie od ich statusu. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from TranslationJob j where j.createdAt < :cutoff")
    int deleteCreatedBefore(@Param("cutoff") Instant cutoff);
}
