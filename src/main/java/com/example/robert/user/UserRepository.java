/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.robert.user;

import com.example.robert.user.dto.AdminUserView;
import com.example.robert.user.model.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
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
    int lockAccountUntil(@Param("email") String email, @Param("until") Instant until);

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
    int clearExpiredLock(@Param("email") String email, @Param("now") Instant now);

    /* ---------------------------------------------------------------------------------
     * Panel administracyjny.
     *
     * Odczyty idą PROJEKCJĄ, nie encją - ta sama zasada co w TranslationJobRepository,
     * ale tutaj powodem nie jest rozmiar wiersza, tylko hash hasła: encja User jest
     * jednocześnie UserDetails i zwrócona z kontrolera wypuściłaby hash do API.
     * --------------------------------------------------------------------------------- */

    /**
     * Lista kont dla panelu, filtrowana po fragmencie adresu.
     *
     * lower(u.email) po stronie KOLUMNY, mimo że EmailNormalizer sprowadza nowe adresy do
     * małych liter przy zapisie: wiersze sprzed wprowadzenia normalizacji mogą mieć wielkie
     * litery, a PostgreSQL porównuje teksty z rozróżnianiem wielkości. Bez tego administrator
     * szukający "kowalski" nie znalazłby konta zapisanego jako "Kowalski@example.com" -
     * czyli filtr milczałby zamiast odpowiedzieć. Regresja: AdminPanelTest.search_shouldIgnoreCase.
     *
     * ESCAPE '!' zamiast domyślnego backslasha: PostgreSQL i H2 przyjmują backslash same
     * z siebie, ale to zachowanie domyślne silnika, a nie kontrakt zapytania - a HQL traktuje
     * backslash w literale znakowym jako początek sekwencji ucieczki, więc zapis samego
     * znaku jest tam dwuznaczny. Wykrzyknik jest jednoznaczny w obu warstwach. Wzorzec
     * buduje UserService.likePattern, który escape'uje %, _ oraz sam znak ucieczki - bez
     * tego q=% zwracałoby całą bazę, czyli filtr po cichu przestawałby filtrować.
     *
     * countQuery podany jawnie - przy wyrażeniu konstruktora przepisanie zapytania na count
     * przez Spring Data jest zawodne (ta sama uwaga co przy findSummaries).
     */
    @Query(value = """
            select new com.example.robert.user.dto.AdminUserView(
                u.id, u.name, u.email, u.role, u.createdAt,
                u.blockedAt, u.blockedReason, u.failedLoginAttempts, u.lockedUntil)
            from User u
            where lower(u.email) like :pattern escape '!'
            order by u.id
            """,
            countQuery = """
                    select count(u) from User u
                    where lower(u.email) like :pattern escape '!'
                    """)
    Page<AdminUserView> findAdminViews(@Param("pattern") String pattern, Pageable pageable);

    @Query("""
            select new com.example.robert.user.dto.AdminUserView(
                u.id, u.name, u.email, u.role, u.createdAt,
                u.blockedAt, u.blockedReason, u.failedLoginAttempts, u.lockedUntil)
            from User u
            where u.id = :id
            """)
    Optional<AdminUserView> findAdminView(@Param("id") Long id);

    /**
     * Nakłada blokadę administracyjną.
     *
     * Warunek "blockedAt is null" czyni operację idempotentną i chroni ŚLAD AUDYTOWY:
     * ponowne zablokowanie już zablokowanego konta nie podmienia powodu ani daty na nowe,
     * więc informacja o tym, kiedy i za co konto padło, nie ginie przy przypadkowym
     * dwukliku. Regresja: AdminPanelTest.block_shouldBeIdempotent.
     *
     * @return 1 przy faktycznej zmianie, 0 gdy konto było już zablokowane albo nie istnieje -
     *         wołający rozróżnia te dwa przypadki wcześniejszym odczytem, bo tylko on
     *         odróżnia "nie ma takiego konta" od "nic nie trzeba było robić"
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update User u set u.blockedAt = :now, u.blockedReason = :reason
            where u.id = :id and u.blockedAt is null
            """)
    int blockAccount(@Param("id") Long id, @Param("now") Instant now, @Param("reason") String reason);

    /**
     * Zdejmuje blokadę administracyjną - i wyłącznie ją.
     *
     * Nie rusza failedLoginAttempts ani lockedUntil: to osobny stan, zdejmowany osobną
     * akcją panelu. Odblokowanie konta nie ma prawa przy okazji kasować śladu po serii
     * nieudanych logowań, bo to może być właśnie ten trop, przez który konto zablokowano.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update User u set u.blockedAt = null, u.blockedReason = null where u.id = :id")
    int unblockAccount(@Param("id") Long id);

    /** Wariant clearLoginFailures po id - panel operuje na identyfikatorach, nie na adresach. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update User u set u.failedLoginAttempts = 0, u.lockedUntil = null where u.id = :id")
    int clearLoginLock(@Param("id") Long id);

    /**
     * Identyfikatory administratorów, którzy NIE są zablokowani - odczyt z blokadą wierszy.
     *
     * PESSIMISTIC_WRITE, bo bez niego dwóch administratorów blokujących się nawzajem
     * w tej samej chwili przeczytałoby ten sam stan ("jest nas dwóch, więc wolno") i obaj
     * przeszliby kontrolę. Efektem byłoby zero niezablokowanych administratorów, a wyjściem
     * z tego stanu wyłącznie ręczny UPDATE w bazie: AdminBootstrap z założenia NIE promuje
     * istniejącego konta USER na ADMIN, więc aplikacja sama by się z tego nie podniosła.
     *
     * Bez SKIP LOCKED, w odróżnieniu od kolejek: tam chodzi o rozdzielenie pracy między
     * instancje, tutaj drugi wołający ma POCZEKAĆ i zobaczyć wynik pierwszego.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select u.id from User u
            where u.role = com.example.robert.user.model.Role.ADMIN and u.blockedAt is null
            """)
    List<Long> lockUnblockedAdminIds();
}
