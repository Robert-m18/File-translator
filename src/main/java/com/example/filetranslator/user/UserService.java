/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.filetranslator.user;

import com.example.filetranslator.common.validation.EmailNormalizer;
import com.example.filetranslator.user.dto.AdminUserView;
import com.example.filetranslator.user.model.Role;
import com.example.filetranslator.user.model.User;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;


/**
 * Operacje na kontach użytkowników - jedyne wejście do tabeli users.
 *
 * Klasa nie udostępnia ogólnego CRUD-u (edycji dowolnego pola, kasowania bez kontroli).
 * Każda metoda odpowiada konkretnemu przypadkowi użycia i ma wołającego w kodzie: pakiety
 * auth/ i admin/ korzystają wyłącznie z tego serwisu, nie z repozytorium. Dzięki temu
 * niezmienniki konta - kto nadaje rolę, kiedy konto staje się aktywne, co dzieje się
 * z hasłem - są opisane w jednym miejscu, a nie rozproszone po wołających.
 */
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;

    /** Pojemność kolumny users.name. */
    private static final int MAX_NAME_LENGTH = 50;

    /**
     * Zakłada konto potwierdzone, na podstawie zgłoszenia z poczekalni rejestracyjnej.
     *
     * Hasło przyjmowane jest już zahashowane, ponieważ BCrypt policzono przy przyjęciu
     * zgłoszenia. Ponowne kodowanie dałoby hash hasha i uniemożliwiło logowanie.
     *
     * Nie istnieje odpowiednik zakładający konto nieaktywne: konto powstaje wyłącznie
     * w chwili potwierdzenia adresu, a wcześniej dane czekają w pending_registrations.
     * Wiersz z enabled = false jest w tej aplikacji stanem niemożliwym.
     */
    @Transactional
    public User createConfirmedUser(String email, String name, String passwordHash) {
        User user = new User();
        user.setEmail(email);
        user.setName(name);
        user.setPassword(passwordHash);
        user.setRole(Role.USER);
        user.setEnabled(true);
        return userRepository.save(user);
    }

    /**
     * Zakłada konto administratora. Wołane wyłącznie przy starcie aplikacji.
     *
     * Osobna metoda zamiast parametru Role w metodzie powyżej wynika z zasady, że rola nigdy
     * nie pochodzi z danych wejściowych. Jedyna ścieżka nadająca uprawnienia administratora
     * jest dzięki temu rozpoznawalna po nazwie i nieosiągalna z przepływu rejestracji;
     * wspólna metoda z parametrem oznaczałaby, że jedno błędne wywołanie w kontrolerze
     * pozwala rejestracji zakładać administratorów.
     *
     * Hasło przyjmowane jest zahashowane, symetrycznie do pozostałych metod zakładających
     * konto - inaczej trzeba by pamiętać, która z sąsiadujących metod koduje, a która nie.
     */
    @Transactional
    public User createAdmin(String email, String name, String passwordHash) {
        User admin = new User();
        admin.setEmail(email);
        admin.setName(name);
        admin.setPassword(passwordHash);
        admin.setRole(Role.ADMIN);
        // Konto aktywne od razu: konta technicznego nikt nie potwierdzi klikając w link.
        admin.setEnabled(true);
        return userRepository.save(admin);
    }

    /**
     * Odnajduje lub zakłada konto na podstawie potwierdzonej tożsamości z Google.
     *
     * Trzecia metoda zakładająca konto, osobna z tego samego powodu co dwie poprzednie: rola
     * nie pochodzi z danych wejściowych, więc każda ścieżka ją nadająca ma być widoczna
     * po nazwie. Wspólna metoda z parametrem Role oznaczałaby, że jedno błędne wywołanie
     * w obsłudze logowania pozwala zewnętrznemu dostawcy tożsamości zakładać administratorów.
     *
     * Kolejność wyszukiwania jest istotna - najpierw identyfikator konta Google, potem adres:
     *
     *  1. Po identyfikatorze - konto jest już powiązane. Adres mógł się w międzyczasie
     *     zmienić u dostawcy i nie ma to znaczenia, bo tożsamością jest identyfikator.
     *  2. Po adresie - konto istnieje, założone hasłem; powiązanie zostaje dopisane. Jest to
     *     świadoma decyzja o łączeniu kont: Google z potwierdzonym adresem dowodzi kontroli
     *     nad tą samą skrzynką, co kliknięcie w link potwierdzający przy rejestracji, więc
     *     nie pojawia się tu nowe zaufanie. Warunek potwierdzonego adresu sprawdza wołający
     *     i bez niego ta gałąź byłaby przejęciem konta, a nie połączeniem.
     *  3. Brak konta - zostaje założone, od razu aktywne.
     *
     * Konto z Google nie dostaje maila potwierdzającego, ponieważ adres potwierdził już
     * dostawca tożsamości.
     *
     * Hasło przyjmowane jest zahashowane, symetrycznie do pozostałych metod. Wołający podaje
     * hash losowego, porzuconego sekretu, dzięki czemu kolumna hasła pozostaje niepusta,
     * a logowanie hasłem na takie konto zwraca zwykły błąd poświadczeń.
     *
     * @param email adres już znormalizowany przez wołającego - nie przychodzi tu przez DTO,
     *              więc normalizacja z kompaktowego konstruktora go nie objęła, a PostgreSQL
     *              porównuje teksty z rozróżnianiem wielkości liter
     */
    @Transactional
    public User findOrCreateGoogleUser(String googleSub, String email, String name, String passwordHash) {
        Optional<User> bySub = userRepository.findByGoogleSub(googleSub);
        if (bySub.isPresent()) {
            return bySub.get();
        }

        Optional<User> byEmail = userRepository.findByEmail(email);
        if (byEmail.isPresent()) {
            User existing = byEmail.get();
            existing.setGoogleSub(googleSub);
            return userRepository.save(existing);
            // Nazwa nie jest nadpisywana nazwą z Google: konto należy już do kogoś, kto mógł
            // ją zmienić. Logowanie nie jest miejscem na cichą edycję cudzych danych.
        }

        User user = new User();
        user.setGoogleSub(googleSub);
        user.setEmail(email);
        user.setName(displayName(name, email));
        user.setPassword(passwordHash);
        user.setRole(Role.USER);
        user.setEnabled(true);
        return userRepository.save(user);
    }

    /**
     * Buduje nazwę do wyświetlania, przyciętą do pojemności kolumny.
     *
     * Kolumna ma ograniczoną długość, a dostawca tożsamości żadnego limitu nie stosuje. Bez
     * przycięcia dłuższe imię i nazwisko kończyłoby się naruszeniem więzów, czyli błędem 500
     * w środku przekierowania z Google - w miejscu, w którym użytkownik nie ma czego ponowić,
     * bo kod autoryzacyjny jest już zużyty.
     *
     * Puste imię (dostawca nie gwarantuje tego roszczenia) zastępuje część adresu przed małpą.
     */
    private String displayName(String name, String email) {
        String candidate = name;
        if (candidate == null || candidate.isBlank()) {
            // Zabezpieczenie na adres bez małpy: taki nie powinien tu dotrzeć, ale wyjątek
            // z substring zamieniłby brzydkie dane w awarię na ścieżce logowania.
            int at = email.indexOf('@');
            candidate = at > 0 ? email.substring(0, at) : email;
        }
        candidate = candidate.trim();
        return candidate.length() > MAX_NAME_LENGTH
                ? candidate.substring(0, MAX_NAME_LENGTH)
                : candidate;
    }

    @Transactional(readOnly = true)
    public boolean existsByEmail(String email) {
        return userRepository.existsByEmail(email);
    }

    @Transactional(readOnly = true)
    public Optional<User> findEntityByEmail(String email) {
        return userRepository.findByEmail(email);
    }

    /**
     * Zwraca konto po identyfikatorze konta Google.
     *
     * Potrzebne przy wystawianiu tokenów po zalogowaniu przez Google i nie jest tożsame
     * z wyszukaniem po adresie z tokenu tożsamości: konto mogło zostać powiązane wcześniej,
     * a adres u dostawcy zmieniony później. Token musi nieść adres z wiersza tej aplikacji,
     * bo po nim odnajdywany jest użytkownik przy każdym kolejnym żądaniu.
     */
    @Transactional(readOnly = true)
    public Optional<User> findEntityByGoogleSub(String googleSub) {
        return userRepository.findByGoogleSub(googleSub);
    }

    /**
     * Podmienia hash hasła. Przyjmuje wartość już zakodowaną, ponieważ kodowanie należy do
     * tego, kto zna hasło jawne - tutaj trafia wyłącznie gotowy hash.
     */
    @Transactional
    public void updatePassword(User user, String newPasswordHash) {
        user.setPassword(newPasswordHash);
        userRepository.save(user);
    }

    /** Zeruje licznik nieudanych logowań i zdejmuje blokadę. Wołane po resecie hasła. */
    @Transactional
    public void clearLoginFailures(String email) {
        userRepository.resetFailedLoginAttempts(email);
    }

    /* ---------------------------------------------------------------------------------
     * Panel administracyjny. Wołane wyłącznie z AdminUserService.
     * --------------------------------------------------------------------------------- */

    /** Znak ucieczki wzorca LIKE. Uzasadnienie wyboru: UserRepository.findAdminViews. */
    private static final char LIKE_ESCAPE = '!';

    @Transactional(readOnly = true)
    public Page<AdminUserView> findAdminViews(String query, Pageable pageable) {
        return userRepository.findAdminViews(likePattern(query), pageable);
    }

    @Transactional(readOnly = true)
    public Optional<AdminUserView> findAdminView(Long id) {
        return userRepository.findAdminView(id);
    }

    /**
     * Nakłada blokadę administracyjną.
     *
     * @return true, jeśli blokada została nałożona; false, jeśli konto było już zablokowane -
     *         operacja jest idempotentna, żeby nie nadpisywać śladu audytowego
     */
    @Transactional
    public boolean blockAccount(Long id, Instant now, String reason) {
        return userRepository.blockAccount(id, now, reason) > 0;
    }

    @Transactional
    public void unblockAccount(Long id) {
        userRepository.unblockAccount(id);
    }

    /** Zdejmuje blokadę po nieudanych logowaniach. Nie rusza blokady administracyjnej. */
    @Transactional
    public void clearLoginLock(Long id) {
        userRepository.clearLoginLock(id);
    }

    /**
     * Kasuje konto razem z sesjami, tokenami resetu i zleceniami tłumaczenia (kaskada
     * po stronie bazy - patrz UserRepository.deleteAccount).
     *
     * Wymaga transakcji wołającego: decyzja o dopuszczalności kasowania zapada
     * w AdminUserService pod blokadą wierszy administratorów, a blokada obowiązuje tylko do
     * końca transakcji, w której ją założono. Osobna transakcja tutaj oznaczałaby, że kontrola
     * i kasowanie odbywają się w dwóch różnych stanach bazy.
     *
     * @return true, jeśli konto istniało i zostało skasowane
     */
    @Transactional
    public boolean deleteAccount(Long id) {
        return userRepository.deleteAccount(id) > 0;
    }

    /**
     * Zwraca identyfikatory niezablokowanych administratorów, blokując ich wiersze na czas
     * transakcji. Wymaga transakcji wołającego - inaczej blokada zwalnia się, zanim zapadnie
     * decyzja, której miała pilnować.
     */
    @Transactional
    public List<Long> lockUnblockedAdminIds() {
        return userRepository.lockUnblockedAdminIds();
    }

    /**
     * Zamienia fragment adresu wpisany przez administratora we wzorzec LIKE.
     *
     * Wykonuje dwie rzeczy, obie konieczne. Normalizacja sprawia, że zapytania różniące się
     * wielkością liter i białymi znakami szukają tego samego; po stronie kolumny odpowiada jej
     * funkcja lower() w zapytaniu. Escapowanie metaznaków zapobiega sytuacji, w której wpisanie
     * procentu zwraca całą bazę, a podkreślnik pasuje do dowolnego znaku - filtr przestawałby
     * wtedy filtrować w sposób niewidoczny dla administratora.
     *
     * Puste zapytanie daje wzorzec pasujący do wszystkiego, dzięki czemu lista z filtrem
     * i bez filtra to jedno zapytanie, bez dynamicznie sklejanego SQL-a i bez dwóch ścieżek,
     * które mogłyby się rozjechać.
     */
    private static String likePattern(String query) {
        String normalized = EmailNormalizer.normalize(query);
        if (normalized == null || normalized.isEmpty()) {
            return "%";
        }

        StringBuilder pattern = new StringBuilder(normalized.length() + 8).append('%');
        for (char c : normalized.toCharArray()) {
            // Sam znak ucieczki również wymaga poprzedzenia - inaczej adres zawierający
            // wykrzyknik szukałby czegoś innego, niż wpisano.
            if (c == '%' || c == '_' || c == LIKE_ESCAPE) {
                pattern.append(LIKE_ESCAPE);
            }
            pattern.append(c);
        }
        return pattern.append('%').toString();
    }
}
