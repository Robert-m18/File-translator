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

    @Transactional(readOnly = true)
    public boolean existsByEmail(String email) {
        return userRepository.existsByEmail(email);
    }

    @Transactional(readOnly = true)
    public Optional<User> findEntityByEmail(String email) {
        return userRepository.findByEmail(email);
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