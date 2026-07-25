/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.robert.auth;

import com.example.robert.common.security.AccountLockProperties;
import com.example.robert.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Liczy nieudane próby logowania i blokuje konto po przekroczeniu progu.
 *
 * Samo sprawdzanie blokady nie jest tutaj - robi je User.isAccountNonLocked(),
 * wołane przez Spring Security jeszcze PRZED porównaniem hasła. Ta klasa tylko
 * aktualizuje liczniki w reakcji na zdarzenia uwierzytelniania.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoginAttemptService {

    private final UserRepository userRepository;
    private final AccountLockProperties properties;

    /**
     * REQUIRES_NEW jest tu kluczowe, nie kosmetyczne.
     *
     * Zdarzenie o nieudanym logowaniu powstaje wewnątrz authenticationManager.authenticate(),
     * czyli w środku transakcji AuthService.login - która zaraz potem zostaje wycofana,
     * bo uwierzytelnienie kończy się wyjątkiem. Przy domyślnym REQUIRED inkrementacja
     * licznika dołączała do tej transakcji i znikała razem z nią: licznik stale pokazywał 0,
     * a konto nigdy się nie blokowało.
     *
     * Osobna transakcja zapisuje się niezależnie od losu tej, która ją wywołała.
     * To ogólna zasada dla liczników i wpisów audytowych: mają przetrwać rollback
     * operacji, którą opisują.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(String email) {
        if (!properties.enabled()) {
            return;
        }

        int updated = userRepository.incrementFailedLoginAttempts(email);
        if (updated == 0) {
            // Nie ma takiego konta. Świadomie nie prowadzimy licznika dla nieistniejących
            // adresów - byłby to kanał do sprawdzania, które adresy są zarejestrowane.
            return;
        }

        int attempts = userRepository.findFailedLoginAttempts(email).orElse(0);
        if (attempts >= properties.maxAttempts()) {
            userRepository.lockAccountUntil(email, LocalDateTime.now().plus(properties.lockDuration()));
            log.warn("Konto zablokowane na {} po {} nieudanych próbach logowania",
                    properties.lockDuration(), attempts);
        }
    }

    @Transactional
    public void recordSuccess(String email) {
        if (!properties.enabled()) {
            return;
        }
        // Liczymy KOLEJNE nieudane próby, więc udane logowanie zeruje licznik.
        // Bez tego konto używane od lat zablokowałoby się po zsumowaniu literówek.
        userRepository.resetFailedLoginAttempts(email);
    }
}
