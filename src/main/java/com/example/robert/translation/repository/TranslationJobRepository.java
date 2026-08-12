/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.robert.translation.repository;

import com.example.robert.translation.TranslationCompletedEvent;
import com.example.robert.translation.TranslationProperties;
import com.example.robert.translation.dto.TranslationCacheHit;
import com.example.robert.translation.dto.TranslationJobResponse;
import com.example.robert.translation.dto.TranslationResultView;
import com.example.robert.translation.model.TargetLanguage;
import com.example.robert.translation.model.TranslationJob;
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
 * Dostęp do zleceń tłumaczenia.
 *
 * Dwie zasady obowiązujące w całej tej klasie:
 *
 * 1. KAŻDY odczyt dla użytkownika bierze userId do WHERE. Nigdy "pobierz po id i porównaj
 *    właściciela w kodzie" - wtedy 404 i 403 są rozróżnialne z zewnątrz i API daje się użyć
 *    do sprawdzania, które identyfikatory istnieją. Tak samo jak moduł auth nie zdradza,
 *    które adresy email są zarejestrowane.
 *
 * 2. Odczyty prezentacyjne idą przez projekcje, nie przez encję. TranslationJob niesie dwie
 *    kolumny po ~1 MB i pobranie go po to, żeby pokazać status, czyta całą treść pliku.
 */
@Repository
public interface TranslationJobRepository extends JpaRepository<TranslationJob, Long> {

    /**
     * Zlecenia gotowe do wzięcia - od razu zablokowane dla tej instancji.
     *
     * FOR UPDATE SKIP LOCKED (tak Hibernate tłumaczy PESSIMISTIC_WRITE z limitem blokady -2)
     * sprawia, że druga instancja czytająca w tym samym momencie przeskoczy zajęte wiersze
     * zamiast czekać albo odczytać ten sam komplet. Ta sama mechanika co
     * OutboxMessageRepository.findReadyToSend - tam jest też pełne uzasadnienie i uwaga
     * o tym, że na PostgreSQL wychodzi z tego "for no key update ... skip locked".
     *
     * PROCESSING jest w warunku razem z PENDING i to nie jest przeoczenie: status mówi
     * użytkownikowi, co się dzieje, a o tym, czy wolno wziąć wiersz, decyduje WYŁĄCZNIE
     * next_attempt_at. Dzięki temu zlecenie porzucone przez proces, który padł w trakcie
     * rozmowy z dostawcą, wraca do obiegu po upływie okna rezerwacji, zamiast zostać
     * w PROCESSING na zawsze.
     *
     * Blokada wisi tylko przez transakcję rezerwacji. Rozmowa z dostawcą toczy się długo
     * po jej zwolnieniu - blokady bazodanowe nigdy nie obejmują operacji zewnętrznej.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2")) // org.hibernate.Timeouts.SKIP_LOCKED
    @Query("""
            select j from TranslationJob j
            where j.status in (com.example.robert.translation.model.TranslationStatus.PENDING,
                               com.example.robert.translation.model.TranslationStatus.PROCESSING)
              and j.nextAttemptAt <= :now
            order by j.id
            """)
    List<TranslationJob> findClaimable(@Param("now") Instant now, Limit limit);

    /**
     * Rezerwuje odczytane zlecenia: przesuwa next_attempt_at w przyszłość, podbija licznik
     * podejść i przestawia status na PROCESSING. Musi lecieć w tej samej transakcji co
     * findClaimable - to blokada z odczytu gwarantuje, że nikt nie wszedł pomiędzy.
     *
     * Warunek na status i next_attempt_at zostaje mimo blokady: kosztuje tyle co nic,
     * a chroni przed wzięciem wiersza, który zmienił stan między odczytem a UPDATE-em.
     *
     * @return ile wierszy faktycznie zarezerwowano
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update TranslationJob j
            set j.attempts = j.attempts + 1,
                j.status = com.example.robert.translation.model.TranslationStatus.PROCESSING,
                j.nextAttemptAt = :reservedUntil
            where j.id in :ids
              and j.status in (com.example.robert.translation.model.TranslationStatus.PENDING,
                               com.example.robert.translation.model.TranslationStatus.PROCESSING)
              and j.nextAttemptAt <= :now
            """)
    int claim(@Param("ids") Collection<Long> ids,
              @Param("now") Instant now,
              @Param("reservedUntil") Instant reservedUntil);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update TranslationJob j
            set j.status = com.example.robert.translation.model.TranslationStatus.DONE,
                j.resultContent = :result,
                j.sourceLang = :sourceLang,
                j.provider = :provider,
                j.completedAt = :now,
                j.lastError = null
            where j.id = :id
            """)
    int markDone(@Param("id") Long id,
                 @Param("result") String result,
                 @Param("sourceLang") String sourceLang,
                 @Param("provider") TranslationProperties.Provider provider,
                 @Param("now") Instant now);

    /** Porażka, ale próbujemy dalej - wiersz wraca do PENDING z odsuniętym next_attempt_at. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update TranslationJob j
            set j.status = com.example.robert.translation.model.TranslationStatus.PENDING,
                j.nextAttemptAt = :nextAttemptAt,
                j.lastError = :error
            where j.id = :id
            """)
    int markRetry(@Param("id") Long id,
                  @Param("nextAttemptAt") Instant nextAttemptAt,
                  @Param("error") String error);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update TranslationJob j
            set j.status = com.example.robert.translation.model.TranslationStatus.FAILED,
                j.completedAt = :now,
                j.lastError = :error
            where j.id = :id
            """)
    int markFailed(@Param("id") Long id,
                   @Param("error") String error,
                   @Param("now") Instant now);

    @Query("""
            select new com.example.robert.translation.dto.TranslationJobResponse(
                j.id, j.originalFilename, j.sourceLang, j.targetLang, j.status,
                j.charCount, j.createdAt, j.completedAt)
            from TranslationJob j
            where j.id = :id and j.user.id = :userId
            """)
    Optional<TranslationJobResponse> findSummary(@Param("id") Long id, @Param("userId") Long userId);

    /**
     * Lista zleceń właściciela, najnowsze pierwsze.
     *
     * countQuery podany jawnie: przy zapytaniu z wyrażeniem konstruktora Spring Data musiałby
     * je przepisać na count, co jest źródłem zaskakujących błędów - łatwiej napisać wprost.
     */
    @Query(value = """
            select new com.example.robert.translation.dto.TranslationJobResponse(
                j.id, j.originalFilename, j.sourceLang, j.targetLang, j.status,
                j.charCount, j.createdAt, j.completedAt)
            from TranslationJob j
            where j.user.id = :userId
            order by j.id desc
            """,
            countQuery = "select count(j) from TranslationJob j where j.user.id = :userId")
    Page<TranslationJobResponse> findSummaries(@Param("userId") Long userId, Pageable pageable);

    /**
     * Dane potrzebne do powiadomienia właściciela, zebrane jednym zapytaniem.
     *
     * DLACZEGO NIE job.getUser() W WORKERZE: encja przychodzi tam z transakcji REZERWACJI,
     * która dawno się zakończyła, a pole user jest leniwym proxy. Sięgnięcie po adres email
     * poza tamtą sesją kończy się LazyInitializationException - i to na ścieżce, która
     * wykonuje się dopiero po udanym tłumaczeniu, czyli najpóźniej jak się da.
     *
     * Ponowne pobranie encji też nie jest odpowiedzią: wciągnęłoby z powrotem treść źródła
     * i wyniku, czyli do 768 KB tekstu po to, żeby odczytać adres i nazwę pliku. Ta projekcja
     * czyta wyłącznie kolumny, które faktycznie jadą do powiadomienia.
     */
    @Query("""
            select new com.example.robert.translation.TranslationCompletedEvent(
                j.id, u.id, u.email, u.name, j.originalFilename, j.targetLang, j.charCount)
            from TranslationJob j join j.user u
            where j.id = :id
            """)
    Optional<TranslationCompletedEvent> findCompletedEvent(@Param("id") Long id);

    /** Jedyny odczyt, który ma prawo wciągnąć treść wyniku. */
    @Query("""
            select new com.example.robert.translation.dto.TranslationResultView(
                j.status, j.originalFilename, j.targetLang, j.resultContent)
            from TranslationJob j
            where j.id = :id and j.user.id = :userId
            """)
    Optional<TranslationResultView> findResult(@Param("id") Long id, @Param("userId") Long userId);

    /**
     * Ile znaków użytkownik zlecił od podanego momentu - pod dobowy limit.
     *
     * coalesce, bo suma z pustego zbioru to NULL, a nie zero: bez tego pierwsze zlecenie
     * nowego użytkownika wywalałoby się na NullPointerException.
     */
    @Query("""
            select coalesce(sum(j.charCount), 0) from TranslationJob j
            where j.user.id = :userId and j.createdAt >= :since
            """)
    long sumCharCountSince(@Param("userId") Long userId, @Param("since") Instant since);

    /**
     * Czy użytkownik ma już gotowe tłumaczenie tej treści - sam fakt, bez treści wyniku.
     *
     * To zapytanie leci na ŚCIEŻCE ŻĄDANIA (TranslationService.submit sprawdza nim, czy
     * naliczyć dobowy limit znaków), więc nie wolno mu wciągać result_content: odpowiedź
     * "tak" nie jest warta ćwierci megabajta tekstu przeczytanego z dysku. Od kopiowania
     * wyniku jest findCached, wołane później i już poza żądaniem.
     *
     * Klucz to userId + odcisk + język docelowy + DOSTAWCA. Ten ostatni nie jest ozdobą:
     * bez niego wynik atrapy (ECHO) zaspokoiłby zlecenie kierowane do DEEPL - jedyny
     * przypadek, w którym deduplikacja oddaje wynik błędny zamiast szybkiego.
     */
    @Query("""
            select count(j) > 0 from TranslationJob j
            where j.user.id = :userId
              and j.contentHash = :contentHash
              and j.targetLang = :targetLang
              and j.provider = :provider
              and j.status = com.example.robert.translation.model.TranslationStatus.DONE
              and j.resultContent is not null
            """)
    boolean existsCached(@Param("userId") Long userId,
                         @Param("contentHash") String contentHash,
                         @Param("targetLang") TargetLanguage targetLang,
                         @Param("provider") TranslationProperties.Provider provider);

    /**
     * Gotowy wynik do przepisania na zlecenie o podanym id.
     *
     * KLUCZ BIERZE SIĘ Z SAMEGO WIERSZA ZLECENIA (złączenie po user_id, content_hash
     * i target_lang), a nie z parametrów podanych przez wołającego. Powód jest ten sam,
     * dla którego istnieje findCompletedEvent: encja, którą trzyma worker, pochodzi
     * z ZAMKNIĘTEJ transakcji rezerwacji, więc job.getUser() jest tam martwym proxy -
     * sięgnięcie po identyfikator właściciela działa tylko przez szczegół implementacyjny
     * Hibernate'a i jest dokładnie tą pułapką, którą ten pakiet już raz rozbroił.
     *
     * Warunki na status, provider i treść muszą pozostać takie same jak w existsCached.
     * Rozjazd między nimi znaczyłby, że dobowy limit znaków pomijamy na podstawie trafienia,
     * którego potem nie ma - czyli zlecenie przyjęte jako darmowe idzie jednak do dostawcy.
     *
     * done.id <> job.id jest asekuracją: zlecenie w trakcie ma status PROCESSING, więc samo
     * siebie i tak nie zaspokoi, ale ponowne przepuszczenie wiersza już zakończonego nie ma
     * prawa zamienić się w kopiowanie wyniku z samego siebie.
     *
     * Wiersze sprzed changesetu 0008 mają content_hash równy NULL, a NULL nie równa się
     * niczemu - nigdy nie trafią i nie trzeba ich osobno wykluczać.
     *
     * List + Limit, bo JPQL nie ma "pierwszego" - ten sam idiom co w findClaimable.
     */
    @Query("""
            select new com.example.robert.translation.dto.TranslationCacheHit(
                done.resultContent, done.sourceLang)
            from TranslationJob job
            join TranslationJob done
              on done.user.id = job.user.id
             and done.contentHash = job.contentHash
             and done.targetLang = job.targetLang
            where job.id = :jobId
              and done.id <> job.id
              and done.provider = :provider
              and done.status = com.example.robert.translation.model.TranslationStatus.DONE
              and done.resultContent is not null
            order by done.id desc
            """)
    List<TranslationCacheHit> findCachedFor(@Param("jobId") Long jobId,
                                            @Param("provider") TranslationProperties.Provider provider,
                                            Limit limit);

    /** Kasowanie własnego zlecenia - userId w warunku, żeby nie dało się skasować cudzego. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from TranslationJob j where j.id = :id and j.user.id = :userId")
    int deleteOwned(@Param("id") Long id, @Param("userId") Long userId);

    /** Retencja: kasuje zlecenia starsze niż podany moment, niezależnie od statusu. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from TranslationJob j where j.createdAt < :cutoff")
    int deleteCreatedBefore(@Param("cutoff") Instant cutoff);
}
