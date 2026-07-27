/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.robert.user;

import com.example.robert.user.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    /*
     * Liczniki nieudanych logowań aktualizujemy zapytaniem UPDATE, a nie przez
     * wczytanie encji, zmianę pola i zapis. Powód: dwa równoległe nieudane logowania
     * na to samo konto odczytałyby tę samą wartość i drugi zapis nadpisałby pierwszy
     * (lost update) - licznik rósłby wolniej niż liczba prób, czyli dokładnie tam,
     * gdzie zależy nam na dokładności. UPDATE ... SET x = x + 1 jest atomowy w bazie.
     */

    @Modifying(clearAutomatically = true)
    @Query("update User u set u.failedLoginAttempts = u.failedLoginAttempts + 1 where u.email = :email")
    int incrementFailedLoginAttempts(@Param("email") String email);

    @Modifying(clearAutomatically = true)
    @Query("update User u set u.failedLoginAttempts = 0, u.lockedUntil = null where u.email = :email")
    int resetFailedLoginAttempts(@Param("email") String email);

    @Modifying(clearAutomatically = true)
    @Query("update User u set u.lockedUntil = :until where u.email = :email")
    int lockAccountUntil(@Param("email") String email, @Param("until") LocalDateTime until);

    @Query("select u.failedLoginAttempts from User u where u.email = :email")
    Optional<Integer> findFailedLoginAttempts(@Param("email") String email);

    /**
     * Zdejmuje blokadę, która już wygasła, i zeruje przy tym licznik.
     *
     * Bez tego blokada praktycznie się nie kończyła. Licznik zerował wyłącznie UDANY
     * login, więc po upływie lockedUntil zostawał na progu (np. 5 z 5) i pierwsza
     * literówka dawała 6 >= 5, czyli natychmiastową blokadę na kolejne 15 minut.
     * Użytkownik, który nie pamięta hasła dokładnie, nie miał jak z tej pętli wyjść.
     *
     * Warunek na lockedUntil sprawia, że zapytanie nie rusza kont, które jeszcze
     * odsiadują blokadę, ani tych, które nigdy nie były zablokowane.
     */
    @Modifying(clearAutomatically = true)
    @Query("""
            update User u set u.failedLoginAttempts = 0, u.lockedUntil = null
            where u.email = :email and u.lockedUntil is not null and u.lockedUntil < :now
            """)
    int clearExpiredLock(@Param("email") String email, @Param("now") LocalDateTime now);
}
