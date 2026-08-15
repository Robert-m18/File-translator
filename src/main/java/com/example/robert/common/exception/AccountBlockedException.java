/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.robert.common.exception;

import org.springframework.security.authentication.LockedException;

/**
 * Konto zostało zablokowane przez administratora.
 *
 * DZIEDZICZY PO LockedException, i to z dwóch powodów naraz:
 *
 * 1. Rzucany jest z UserDetailsChecker wpiętego w DaoAuthenticationProvider, a ten opakowuje
 *    KAŻDY wyjątek niebędący AuthenticationException w InternalAuthenticationServiceException,
 *    czyli zamienia go w odpowiedź 500. Zwykły RuntimeException dałby więc błąd serwera
 *    zamiast komunikatu dla użytkownika.
 * 2. Fail-safe: gdyby handleAccountBlocked kiedyś zniknął z GlobalExceptionHandler,
 *    odpowiedź spadnie do handleLocked, czyli do 423 ACCOUNT_LOCKED - gorszy komunikat,
 *    ale konto pozostaje zamknięte. Osobna gałąź wyjątków oznaczałaby w tej sytuacji 500,
 *    a w najgorszym wariancie - wpuszczenie.
 *
 * Rzucany też z AuthService.refreshToken, gdzie nie ma żadnego providera: tam chodzi
 * wyłącznie o to, żeby zablokowany nie odnawiał sobie sesji przez 7 dni ważności tokenu
 * odświeżającego.
 */
public class AccountBlockedException extends LockedException {

    public AccountBlockedException() {
        super("Konto zostało zablokowane przez administratora");
    }
}
