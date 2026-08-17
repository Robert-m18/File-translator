/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.filetranslator.notification;

import com.example.filetranslator.notification.model.OutboxMessage;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Limit;
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

@Repository
public interface OutboxMessageRepository extends JpaRepository<OutboxMessage, Long> {

    /**
     * Kandydaci do wysłania - od razu zablokowani dla tej instancji.
     *
     * FOR UPDATE SKIP LOCKED (tak Hibernate tłumaczy PESSIMISTIC_WRITE z limitem blokady
     * -2) sprawia, że druga instancja czytająca w tym samym momencie nie zobaczy wierszy
     * zajętych przez pierwszą, tylko przeskoczy do następnych. Bez tego obie instancje
     * odczytałyby ten sam komplet i jedna z nich odpadłaby dopiero przy rezerwacji,
     * marnując całą rundę - przy kilku instancjach to skaluje się w złą stronę.
     *
     * Blokada wisi tylko przez transakcję rezerwacji, czyli przez jeden UPDATE. Rozmowa
     * z SMTP toczy się długo po jej zwolnieniu - blokady bazodanowe nigdy nie obejmują
     * operacji zewnętrznej.
     *
     * Na PostgreSQL Hibernate emituje "... for no key update of m skip locked". FOR NO KEY
     * UPDATE to słabsza blokada niż FOR UPDATE, ale nadal koliduje z innymi blokadami zapisu
     * na tym samym wierszu, więc rozłączność odczytów - jedyna własność, na której nam tu
     * zależy - trzyma się bez zmian.
     *
     * H2 2.4 (testy) obsługuje SKIP LOCKED tak samo - sprawdzone na dwóch połączeniach,
     * nie tylko składniowo. Gdyby to się kiedyś rozjechało, byłby to rozjazd między testami
     * a produkcją dotyczący współbieżności, czyli najgorszy z możliwych; dlatego jest to
     * warunek utrzymania tej wersji H2.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2")) // org.hibernate.Timeouts.SKIP_LOCKED
    @Query("""
            select m from OutboxMessage m
            where m.status = com.example.filetranslator.notification.model.OutboxMessage$Status.NEW
              and m.nextRetryAt <= :now
            order by m.id
            """)
    List<OutboxMessage> findReadyToSend(@Param("now") Instant now, Limit limit);

    /**
     * Rezerwuje odczytane wiersze, odsuwając nextRetryAt w przyszłość i podbijając licznik
     * podejść. Musi lecieć w tej samej transakcji co findReadyToSend - to blokada z odczytu
     * gwarantuje, że nikt inny nie wszedł między odczyt a rezerwację.
     *
     * Warunek na status i nextRetryAt zostaje mimo blokady. Kosztuje tyle co nic, a chroni
     * przed wzięciem wiersza, który zmienił stan między czasem odczytu a wykonaniem UPDATE-u
     * (np. gdy poprzednia rezerwacja tej samej instancji jeszcze nie wygasła).
     *
     * Nie ma statusu "w trakcie wysyłki": gdyby proces padł po rezerwacji, taki status
     * zostałby na wieki i wiersz utknąłby na zawsze. Tutaj wiersz zostaje jako NEW
     * z odsuniętym nextRetryAt, więc wróci sam po upływie okna. Licznik attempts zlicza
     * więc PODEJŚCIA, nie potwierdzone porażki - świadomy wybór na rzecz braku stanów
     * zablokowanych.
     *
     * @return ile wierszy faktycznie zarezerwowano
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update OutboxMessage m
            set m.attempts = m.attempts + 1, m.nextRetryAt = :reservedUntil
            where m.id in :ids
              and m.status = com.example.filetranslator.notification.model.OutboxMessage$Status.NEW
              and m.nextRetryAt <= :now
            """)
    int claim(@Param("ids") Collection<Long> ids,
              @Param("now") Instant now,
              @Param("reservedUntil") Instant reservedUntil);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update OutboxMessage m
            set m.status = com.example.filetranslator.notification.model.OutboxMessage$Status.SENT,
                m.sentAt = :now, m.lastError = null
            where m.id = :id
            """)
    int markSent(@Param("id") Long id, @Param("now") Instant now);

    /** Porażka, ale próbujemy dalej - wiersz zostaje NEW z odsuniętym nextRetryAt. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update OutboxMessage m
            set m.nextRetryAt = :nextRetryAt, m.lastError = :error
            where m.id = :id
            """)
    int markRetry(@Param("id") Long id,
                  @Param("nextRetryAt") Instant nextRetryAt,
                  @Param("error") String error);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update OutboxMessage m
            set m.status = com.example.filetranslator.notification.model.OutboxMessage$Status.FAILED,
                m.lastError = :error
            where m.id = :id
            """)
    int markFailed(@Param("id") Long id, @Param("error") String error);

    /** Do monitoringu: ile wiadomości ostatecznie nie wyszło. */
    @Query("""
            select count(m) from OutboxMessage m
            where m.status = com.example.filetranslator.notification.model.OutboxMessage$Status.FAILED
            """)
    long countFailed();

    /**
     * Usuwa wysłane wiadomości starsze niż podany moment.
     *
     * Kasujemy WYŁĄCZNIE status SENT. FAILED zostaje i to nie jest przeoczenie: countFailed()
     * jest jedyną odpowiedzią na pytanie "czy maile w ogóle wychodzą", więc sprzątaczka
     * zabierająca FAILED wyzerowałaby ten sygnał i awaria dostarczania wyglądałaby jak cisza.
     * NEW też zostaje - to wiadomości jeszcze w obiegu, czekające na próbę albo na backoff.
     *
     * Odliczamy od sentAt, nie od createdAt: retencja dotyczy czasu, jaki minął od wysłania.
     * Wiersz o statusie SENT ma sentAt zawsze ustawione - markSent nadaje jedno i drugie
     * w tym samym UPDATE.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            delete from OutboxMessage m
            where m.status = com.example.filetranslator.notification.model.OutboxMessage$Status.SENT
              and m.sentAt < :cutoff
            """)
    int deleteSentBefore(@Param("cutoff") Instant cutoff);
}
