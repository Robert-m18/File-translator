/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.filetranslator.user;

import com.example.filetranslator.user.dto.AdminUserView;
import com.example.filetranslator.user.model.User;
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

/**
 * Dostęp do tabeli users: wyszukiwanie kont, liczniki logowania i operacje panelu.
 */
@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    /**
     * Zwraca konto powiązane z danym kontem Google (roszczenie "sub" z tokenu tożsamości).
     *
     * Wyszukiwanie po tym identyfikatorze poprzedza wyszukiwanie po adresie, ponieważ to on
     * jest tożsamością, a adres tylko sposobem pierwszego skojarzenia. Adres konta Google
     * można zmienić, więc odwrotna kolejność odcinałaby użytkownika od konta po takiej zmianie.
     */
    Optional<User> findByGoogleSub(String googleSub);

    /*
     * Liczniki nieudanych logowań aktualizowane są zapytaniem UPDATE, a nie przez wczytanie
     * encji, zmianę pola i zapis. Dwa równoległe nieudane logowania na to samo konto
     * odczytałyby tę samą wartość, a drugi zapis nadpisałby pierwszy, przez co licznik rósłby
     * wolniej niż liczba prób - dokładnie tam, gdzie zależy nam na dokładności. Zapytanie
     * postaci "SET x = x + 1" jest w bazie atomowe.
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
     * Zdejmuje blokadę, która już wygasła, i zeruje przy tym licznik prób.
     *
     * Bez tego kroku blokada praktycznie się nie kończyła: licznik zeruje wyłącznie udane
     * logowanie, więc po upływie terminu pozostawał na progu i pierwsza literówka nakładała
     * blokadę natychmiast. Użytkownik, który nie pamięta hasła dokładnie, nie miał jak wyjść
     * z tej pętli.
     *
     * Warunek na termin blokady sprawia, że zapytanie nie rusza kont, które nadal ją odsiadują,
     * ani tych, które nigdy nie były zablokowane.
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
     * Odczyty korzystają z projekcji, nie z encji: encja User jest jednocześnie obiektem
     * UserDetails i niesie hash hasła, więc zwrócona z kontrolera wypuściłaby go do API.
     * --------------------------------------------------------------------------------- */

    /**
     * Zwraca stronę kont dla panelu, filtrowaną po fragmencie adresu.
     *
     * Funkcja lower() po stronie kolumny jest potrzebna mimo normalizacji adresów przy zapisie:
     * wiersze sprzed jej wprowadzenia mogą zawierać wielkie litery, a PostgreSQL porównuje
     * teksty z rozróżnianiem wielkości. Bez niej wyszukiwanie nie znalazłoby konta zapisanego
     * z wielkiej litery, czyli filtr milczałby zamiast odpowiedzieć.
     *
     * Znak ucieczki to wykrzyknik, a nie domyślny odwrotny ukośnik: obie bazy przyjmują ukośnik
     * z własnej inicjatywy, ale jest to zachowanie domyślne silnika, a nie kontrakt zapytania,
     * a HQL traktuje odwrotny ukośnik w literale znakowym jako początek sekwencji ucieczki, więc
     * jego zapis jest tam dwuznaczny. Sam wzorzec buduje UserService, escapując metaznaki -
     * bez tego zapytanie złożone z procentu zwracałoby całą bazę.
     *
     * Zapytanie zliczające podane jest jawnie, ponieważ przy wyrażeniu konstruktora automatyczne
     * przepisanie na count bywa zawodne.
     */
    @Query(value = """
            select new com.example.filetranslator.user.dto.AdminUserView(
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

    /** Pojedyncze konto w widoku panelu - ta sama projekcja co na liście. */
    @Query("""
            select new com.example.filetranslator.user.dto.AdminUserView(
                u.id, u.name, u.email, u.role, u.createdAt,
                u.blockedAt, u.blockedReason, u.failedLoginAttempts, u.lockedUntil)
            from User u
            where u.id = :id
            """)
    Optional<AdminUserView> findAdminView(@Param("id") Long id);

    /**
     * Nakłada blokadę administracyjną.
     *
     * Warunek wykluczający konta już zablokowane czyni operację idempotentną i chroni ślad
     * audytowy: ponowne zablokowanie nie podmienia daty ani powodu, więc informacja o tym,
     * kiedy i za co konto zostało zablokowane, nie ginie przy przypadkowym dwukliku.
     *
     * @return 1 przy faktycznej zmianie, 0 gdy konto było już zablokowane albo nie istnieje;
     *         wołający rozróżnia te przypadki wcześniejszym odczytem, bo tylko on odróżnia
     *         brak konta od braku potrzeby zmiany
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update User u set u.blockedAt = :now, u.blockedReason = :reason
            where u.id = :id and u.blockedAt is null
            """)
    int blockAccount(@Param("id") Long id, @Param("now") Instant now, @Param("reason") String reason);

    /**
     * Zdejmuje blokadę administracyjną i wyłącznie ją.
     *
     * Licznik nieudanych logowań pozostaje nietknięty, bo jest osobnym stanem zdejmowanym
     * osobną akcją panelu. Odblokowanie konta nie powinno kasować śladu po serii nieudanych
     * logowań, ponieważ może to być właśnie powód, dla którego konto zablokowano.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update User u set u.blockedAt = null, u.blockedReason = null where u.id = :id")
    int unblockAccount(@Param("id") Long id);

    /** Zeruje licznik nieudanych logowań po identyfikatorze - panel operuje na id, nie na adresach. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update User u set u.failedLoginAttempts = 0, u.lockedUntil = null where u.id = :id")
    int clearLoginLock(@Param("id") Long id);

    /**
     * Kasuje konto nieodwracalnie, razem ze wszystkim, co od niego zależy.
     *
     * Kaskada realizowana jest po stronie bazy: klucze obce w refresh_tokens,
     * password_reset_tokens, verification_tokens i translation_jobs mają ON DELETE CASCADE,
     * więc wiersze zależne znikają w tej samej transakcji co konto. Kasowanie ich osobno
     * z aplikacji byłoby czterema zapytaniami zamiast jednego, a awaria w połowie zostawiłaby
     * konto bez sesji i bez zleceń - stan gorszy niż jedno i drugie.
     *
     * Zapytanie JPQL zamiast deleteById, ponieważ nie ma potrzeby wczytywania encji tylko po
     * to, żeby ją skasować (encja niesie hash hasła), a liczba zmienionych wierszy odróżnia
     * skasowanie od sytuacji, w której konta już nie było.
     *
     * Zapytanie nie dotyka poczekalni rejestracyjnej ani skrzynki nadawczej. Poczekalnia jest
     * kluczowana adresem i zgłoszenia na adres z istniejącym kontem nie powstają, a skrzynka
     * ma własną retencję krótszą niż ważność czegokolwiek, co dałoby się z niej odczytać.
     *
     * @return 1 przy skasowaniu, 0 gdy konta już nie było
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from User u where u.id = :id")
    int deleteAccount(@Param("id") Long id);

    /**
     * Zwraca identyfikatory niezablokowanych administratorów, blokując ich wiersze do końca
     * transakcji.
     *
     * Blokada zapisu jest konieczna, ponieważ bez niej dwóch administratorów działających na
     * siebie nawzajem w tej samej chwili odczytałoby ten sam stan i obaj przeszliby kontrolę
     * "czy zostanie choć jeden". Efektem byłby brak jakiegokolwiek niezablokowanego
     * administratora, a jedynym wyjściem ręczna zmiana w bazie - proces startowy celowo nie
     * promuje istniejącego konta zwykłego użytkownika na administratora.
     *
     * W odróżnieniu od kolejek nie ma tu pomijania zablokowanych wierszy: drugi wołający ma
     * poczekać i zobaczyć wynik pierwszego, a nie ominąć go.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select u.id from User u
            where u.role = com.example.filetranslator.user.model.Role.ADMIN and u.blockedAt is null
            """)
    List<Long> lockUnblockedAdminIds();
}
