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
 * Operacje na kontach użytkowników.
 *
 * Nie ma tu CRUD-u administracyjnego w dawnym rozumieniu (edycja dowolnego pola, usuwanie).
 * Był, ale bez kontrolera nikt go nie wołał, a martwy kod obok kodu bezpieczeństwa jest
 * gorszy niż jego brak: nie wiadomo, czy przeszedł ten sam przegląd co reszta.
 *
 * Metody dla panelu administracyjnego (sekcja niżej) są tego przeciwieństwem: każda ma
 * wołającego w pakiecie admin/, własny endpoint i własny test regresyjny. Pakiet admin/
 * NIE sięga do UserRepository - tabela users należy do tego pakietu i całe wejście do niej
 * prowadzi tędy.
 */
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;

    /** Ile znaków mieści kolumna users.name - patrz changeset 0001. */
    private static final int MAX_NAME_LENGTH = 50;

    /**
     * Zakłada konto już potwierdzone, na podstawie zgłoszenia z poczekalni.
     *
     * Hasło przychodzi ZAHASHOWANE - BCrypt policzono już przy przyjęciu zgłoszenia,
     * więc kodowanie go tutaj po raz drugi dałoby hash hasha i uniemożliwiło logowanie.
     *
     * Nie ma tu odpowiednika dawnego saveUser(dto) zakładającego konto z enabled=false.
     * Taki wiersz jest teraz stanem niemożliwym: konto powstaje wyłącznie w chwili
     * potwierdzenia adresu, a wcześniej dane leżą w pending_registrations.
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
     * Zakłada konto administratora. Wołane wyłącznie z AdminBootstrap przy starcie.
     *
     * Osobna metoda, a nie parametr Role w createConfirmedUser, i to jest tu cała decyzja:
     * rola nigdy nie pochodzi z danych wejściowych, więc jedyna ścieżka nadająca ADMIN ma
     * być widoczna po nazwie i nieosiągalna z przepływu rejestracji. Wspólna metoda
     * z parametrem oznaczałaby, że wystarczy jedno błędne wywołanie w kontrolerze, żeby
     * rejestracja zaczęła zakładać administratorów.
     *
     * Hasło przychodzi ZAHASHOWANE, symetrycznie do createConfirmedUser - inaczej trzeba by
     * pamiętać, która z dwóch sąsiadujących metod koduje, a która nie.
     */
    @Transactional
    public User createAdmin(String email, String name, String passwordHash) {
        User admin = new User();
        admin.setEmail(email);
        admin.setName(name);
        admin.setPassword(passwordHash);
        admin.setRole(Role.ADMIN);
        // enabled = true od razu: konta technicznego nikt nie potwierdzi klikając w link,
        // a wiersz z enabled = false jest w tej aplikacji stanem niemożliwym.
        admin.setEnabled(true);
        return userRepository.save(admin);
    }

    /**
     * Odnajduje albo zakłada konto na podstawie potwierdzonej tożsamości z Google.
     *
     * TRZECIA metoda zakładająca konto, obok createConfirmedUser i createAdmin, i osobna
     * z dokładnie tego samego powodu co tamte dwie: rola NIGDY nie pochodzi z danych
     * wejściowych, więc każda ścieżka ją nadająca ma być widoczna po nazwie. Wspólna metoda
     * z parametrem Role oznaczałaby, że jedno błędne wywołanie w obsłudze logowania Google
     * wystarczy, żeby zewnętrzny dostawca tożsamości zaczął zakładać administratorów.
     *
     * KOLEJNOŚĆ SZUKANIA JEST ISTOTNA - najpierw "sub", potem dopiero adres:
     *
     *  1. Po google_sub - konto już powiązane. Adres mógł się od tego czasu zmienić
     *     u Google i to jest w porządku, bo tożsamością jest "sub".
     *  2. Po adresie - konto istnieje, założone hasłem. DOPISUJEMY powiązanie i wpuszczamy.
     *     To jest świadoma decyzja o łączeniu kont, nie efekt uboczny: Google z
     *     email_verified = true dowodzi kontroli nad tą samą skrzynką, co kliknięcie w link
     *     potwierdzający przy rejestracji. Nie ma tu NOWEGO zaufania - jest to samo zaufanie,
     *     którym ta aplikacja już się posługuje. Warunek email_verified sprawdza wołający
     *     i bez niego ta gałąź byłaby przejęciem konta, a nie połączeniem.
     *  3. Brak konta - zakładamy je, od razu włączone.
     *
     * enabled = true I ŻADNEGO MAILA POTWIERDZAJĄCEGO: adres właśnie został potwierdzony
     * przez Google, więc wysłanie linku potwierdzającego adres byłoby prośbą o potwierdzenie
     * potwierdzenia. Wiersz z enabled = false pozostaje stanem niemożliwym.
     *
     * Hasło przychodzi ZAHASHOWANE, symetrycznie do obu sąsiednich metod. Wołający podaje
     * hash losowego, porzuconego sekretu - dlaczego akurat tak, zamiast NULL-a w kolumnie,
     * wyjaśnia changeset 0014-users-google-account.xml.
     *
     * @param email  adres JUŻ ZNORMALIZOWANY przez wołającego (nie przychodzi tu przez DTO,
     *               więc kompaktowy konstruktor go nie tknął, a PostgreSQL rozróżnia wielkość liter)
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
            // Nazwy CELOWO nie nadpisujemy nazwą z Google: konto jest już czyjeś, a użytkownik
            // mógł ją u nas zmienić. Logowanie nie jest miejscem na cichą edycję cudzych danych.
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
     * Nazwa do wyświetlania, przycięta do pojemności kolumny.
     *
     * users.name to VARCHAR(50) NOT NULL, a Google żadnego takiego limitu nie ma. Bez
     * przycięcia dłuższe imię i nazwisko daje DataIntegrityViolationException, czyli 500
     * W ŚRODKU PRZEKIEROWANIA Z GOOGLE - w miejscu, w którym użytkownik nie ma nawet czego
     * ponowić, bo kod autoryzacyjny jest już zużyty.
     *
     * Puste imię (Google nie gwarantuje roszczenia "name") zastępuje część adresu przed @.
     */
    private String displayName(String name, String email) {
        String candidate = name;
        if (candidate == null || candidate.isBlank()) {
            // indexOf świadomie z zabezpieczeniem: adres bez @ nie powinien tu dotrzeć,
            // ale substring(0, -1) rzuciłby wyjątek w środku przekierowania z Google,
            // czyli zamienił brzydkie dane w awarię 500 na ścieżce logowania.
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
     * Konto po identyfikatorze konta Google.
     *
     * Potrzebne przy wystawianiu tokenów po zalogowaniu przez Google, i to NIE jest
     * to samo co wyszukanie po adresie z tokenu ID: konto mogło zostać powiązane dawniej,
     * a adres u Google zmieniony później. Token musi nieść adres z NASZEGO wiersza, bo to
     * po nim UserDetailsServiceImpl odnajduje użytkownika przy każdym kolejnym żądaniu.
     */
    @Transactional(readOnly = true)
    public Optional<User> findEntityByGoogleSub(String googleSub) {
        return userRepository.findByGoogleSub(googleSub);
    }

    /**
     * Podmienia hash hasła. Przyjmuje wartość już zakodowaną, bo kodowanie należy do
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
     * @return true, jeśli blokada faktycznie została nałożona; false, jeśli konto było
     *         już zablokowane (operacja idempotentna - patrz UserRepository.blockAccount)
     */
    @Transactional
    public boolean blockAccount(Long id, Instant now, String reason) {
        return userRepository.blockAccount(id, now, reason) > 0;
    }

    @Transactional
    public void unblockAccount(Long id) {
        userRepository.unblockAccount(id);
    }

    /** Zdejmuje blokadę po nieudanych logowaniach. NIE rusza blokady administracyjnej. */
    @Transactional
    public void clearLoginLock(Long id) {
        userRepository.clearLoginLock(id);
    }

    /**
     * Kasuje konto razem z sesjami, tokenami resetu i zleceniami tłumaczenia (kaskada
     * po stronie bazy - patrz UserRepository.deleteAccount).
     *
     * Musi być wołane w transakcji wołającego, i to nie jest ozdoba: decyzja "czy wolno
     * skasować" zapada w AdminUserService pod blokadą wierszy administratorów, a blokada
     * trzyma tylko do końca transakcji, w której ją założono. Osobna transakcja tutaj
     * znaczyłaby, że kontrola i kasowanie odbywają się w dwóch różnych stanach bazy.
     *
     * @return true, jeśli konto istniało i zostało skasowane
     */
    @Transactional
    public boolean deleteAccount(Long id) {
        return userRepository.deleteAccount(id) > 0;
    }

    /**
     * Identyfikatory niezablokowanych administratorów, z blokadą wierszy na czas transakcji.
     * Musi być wołane w transakcji wołającego - inaczej blokada spada, zanim zapadnie
     * decyzja, której miała pilnować.
     */
    @Transactional
    public List<Long> lockUnblockedAdminIds() {
        return userRepository.lockUnblockedAdminIds();
    }

    /**
     * Zamienia fragment adresu wpisany przez administratora we wzorzec LIKE.
     *
     * Dwie rzeczy, obie konieczne:
     *
     * 1. NORMALIZACJA (EmailNormalizer) - żeby "Kowalski" i " kowalski " szukały tego samego.
     *    Po stronie kolumny odpowiada jej lower(u.email) w zapytaniu.
     * 2. ESCAPOWANIE metaznaków - bez niego wpisanie "%" zwraca całą bazę, a "_" pasuje do
     *    dowolnego znaku. Filtr przestawałby wtedy filtrować po CICHU, czyli w sposób,
     *    którego administrator nie ma jak zauważyć. Regresja:
     *    AdminPanelTest.search_shouldTreatWildcardsLiterally.
     *
     * Puste zapytanie daje wzorzec "%", więc lista bez filtra i lista z filtrem to jedno
     * zapytanie - nie ma tu dynamicznie sklejanego SQL-a ani dwóch ścieżek do rozjechania.
     */
    private static String likePattern(String query) {
        String normalized = EmailNormalizer.normalize(query);
        if (normalized == null || normalized.isEmpty()) {
            return "%";
        }

        StringBuilder pattern = new StringBuilder(normalized.length() + 8).append('%');
        for (char c : normalized.toCharArray()) {
            // Sam znak ucieczki też trzeba uciec - inaczej adres z wykrzyknikiem
            // szukałby czegoś innego, niż wpisano.
            if (c == '%' || c == '_' || c == LIKE_ESCAPE) {
                pattern.append(LIKE_ESCAPE);
            }
            pattern.append(c);
        }
        return pattern.append('%').toString();
    }
}