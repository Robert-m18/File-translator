/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.robert.user;

import com.example.robert.user.model.Role;
import com.example.robert.user.model.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;


/**
 * Operacje na kontach użytkowników.
 *
 * Nie ma tu CRUD-u administracyjnego (lista, odczyt po id, edycja, usuwanie). Był, ale bez
 * kontrolera nikt go nie wołał, a martwy kod obok kodu bezpieczeństwa jest gorszy niż jego
 * brak: nie wiadomo, czy przeszedł ten sam przegląd co reszta. Panel administracyjny wróci
 * jako świadomie zaprojektowana funkcja razem z kontrolerem i testami, a nie jako
 * zaparkowane metody czekające na wywołanie.
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
}