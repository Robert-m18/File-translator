/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.robert.notification;

import com.example.robert.notification.model.OutboxMessage;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface OutboxMessageRepository extends JpaRepository<OutboxMessage, Long> {

    /**
     * Kandydaci do wysłania. Sam odczyt niczego nie rezerwuje - o wyłączność bije się
     * dopiero claim() poniżej.
     */
    @Query("""
            select m from OutboxMessage m
            where m.status = com.example.robert.notification.model.OutboxMessage$Status.NEW
              and m.nextRetryAt <= :now
            order by m.id
            """)
    List<OutboxMessage> findReadyToSend(@Param("now") LocalDateTime now, Limit limit);

    /**
     * Rezerwuje wiersz dla tej instancji, odsuwając nextRetryAt w przyszłość.
     *
     * Warunek na nextRetryAt w klauzuli WHERE jest tu całą mechaniką: UPDATE jest w bazie
     * atomowy, więc z dwóch instancji próbujących zabrać ten sam wiersz dokładnie jedna
     * dostanie w odpowiedzi 1, a druga 0 i pominie wiersz. Nie potrzeba do tego
     * SELECT ... FOR UPDATE SKIP LOCKED, którego H2 (testy) nie wspiera tak samo
     * jak MySQL - a rozjazd zachowania między testami a produkcją byłby tu najgorszy
     * z możliwych, bo dotyczyłby współbieżności.
     *
     * Dlatego też nie ma statusu "w trakcie wysyłki": gdyby proces padł po rezerwacji,
     * taki status zostałby na wieki i wiersz utknąłby na zawsze. Tutaj wiersz zostaje
     * jako NEW z odsuniętym nextRetryAt, więc wróci sam po upływie okna. Licznik attempts
     * zlicza więc PODEJŚCIA, nie potwierdzone porażki - i to jest świadomy wybór na rzecz
     * braku stanów zablokowanych.
     *
     * @return 1 jeśli rezerwacja się udała, 0 jeśli ktoś inny był pierwszy
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update OutboxMessage m
            set m.attempts = m.attempts + 1, m.nextRetryAt = :reservedUntil
            where m.id = :id
              and m.status = com.example.robert.notification.model.OutboxMessage$Status.NEW
              and m.nextRetryAt <= :now
            """)
    int claim(@Param("id") Long id,
              @Param("now") LocalDateTime now,
              @Param("reservedUntil") LocalDateTime reservedUntil);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update OutboxMessage m
            set m.status = com.example.robert.notification.model.OutboxMessage$Status.SENT,
                m.sentAt = :now, m.lastError = null
            where m.id = :id
            """)
    int markSent(@Param("id") Long id, @Param("now") LocalDateTime now);

    /** Porażka, ale próbujemy dalej - wiersz zostaje NEW z odsuniętym nextRetryAt. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update OutboxMessage m
            set m.nextRetryAt = :nextRetryAt, m.lastError = :error
            where m.id = :id
            """)
    int markRetry(@Param("id") Long id,
                  @Param("nextRetryAt") LocalDateTime nextRetryAt,
                  @Param("error") String error);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update OutboxMessage m
            set m.status = com.example.robert.notification.model.OutboxMessage$Status.FAILED,
                m.lastError = :error
            where m.id = :id
            """)
    int markFailed(@Param("id") Long id, @Param("error") String error);

    /** Do monitoringu: ile wiadomości ostatecznie nie wyszło. */
    @Query("""
            select count(m) from OutboxMessage m
            where m.status = com.example.robert.notification.model.OutboxMessage$Status.FAILED
            """)
    long countFailed();
}
