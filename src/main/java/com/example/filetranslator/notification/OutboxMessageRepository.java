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
     * Klauzula FOR UPDATE SKIP LOCKED (tak Hibernate tłumaczy blokadę zapisu z limitem -2)
     * sprawia, że druga instancja czytająca w tym samym momencie pomija wiersze zajęte przez
     * pierwszą i przechodzi do następnych. Bez niej obie odczytałyby ten sam komplet, a jedna
     * odpadłaby dopiero przy rezerwacji, marnując całą rundę - przy większej liczbie instancji
     * skaluje się to w złą stronę.
     *
     * Blokada trwa tylko przez transakcję rezerwacji. Rozmowa z serwerem pocztowym toczy się po
     * jej zwolnieniu, bo blokada bazodanowa nigdy nie obejmuje operacji zewnętrznej.
     *
     * Na PostgreSQL powstaje z tego blokada słabsza niż pełna blokada zapisu, ale nadal
     * kolidująca z innymi blokadami zapisu na tym samym wierszu, więc rozłączność odczytów -
     * jedyna istotna tu własność - pozostaje zachowana. Baza używana w testach obsługuje tę
     * klauzulę tak samo, co jest warunkiem tego, żeby testy i produkcja zachowywały się
     * jednakowo w kwestii współbieżności.
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
     * Nie istnieje status oznaczający wysyłkę w toku: gdyby proces padł po rezerwacji, taki
     * status zostałby na stałe i wiersz utknąłby na zawsze. Zamiast tego wiersz pozostaje nowy,
     * z odsuniętym terminem, więc wraca do obiegu samoczynnie po upływie okna rezerwacji.
     * Konsekwencją jest to, że licznik zlicza podejścia, a nie potwierdzone porażki.
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
     * Kasowane są wyłącznie wiadomości wysłane. Nieudane zostają, ponieważ ich liczba jest
     * jedyną odpowiedzią na pytanie, czy poczta w ogóle wychodzi - sprzątanie obejmujące je
     * wyzerowałoby ten sygnał i awaria dostarczania wyglądałaby jak cisza. Wiadomości nowe
     * również zostają, bo są jeszcze w obiegu i czekają na próbę albo na odstęp przed nią.
     *
     * Wiek liczony jest od wysłania, a nie od utworzenia, bo retencja dotyczy czasu, jaki minął
     * od momentu, w którym treść opuściła aplikację.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            delete from OutboxMessage m
            where m.status = com.example.filetranslator.notification.model.OutboxMessage$Status.SENT
              and m.sentAt < :cutoff
            """)
    int deleteSentBefore(@Param("cutoff") Instant cutoff);
}
