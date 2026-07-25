/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.robert.auth;

import com.example.robert.auth.LoginAttemptService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.stereotype.Component;

/**
 * Spina zdarzenia uwierzytelniania Spring Security z licznikiem nieudanych logowań.
 *
 * Dlaczego przez zdarzenia, a nie wprost w AuthService: hasło sprawdza
 * DaoAuthenticationProvider wewnątrz authenticationManager.authenticate(), więc
 * AuthService nie widzi wyniku inaczej niż jako wyjątek. Zdarzenia publikuje sam
 * Spring Security, dzięki czemu liczniki zadziałają też dla ścieżek logowania
 * dodanych później (kod jednorazowy, OAuth2) bez zmian w tej klasie.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthenticationEventListener {

    private final LoginAttemptService loginAttemptService;

    /**
     * Nasłuchujemy konkretnie na błędne dane logowania, a nie na ogólny
     * AbstractAuthenticationFailureEvent. Ten ostatni obejmuje też zdarzenie
     * "konto zablokowane" - zliczanie go przedłużałoby blokadę przy każdej kolejnej
     * próbie i konto faktycznie nigdy by się nie odblokowało.
     */
    @EventListener
    public void onFailure(AuthenticationFailureBadCredentialsEvent event) {
        String email = event.getAuthentication().getName();
        loginAttemptService.recordFailure(email);
    }

    @EventListener
    public void onSuccess(AuthenticationSuccessEvent event) {
        String email = event.getAuthentication().getName();
        loginAttemptService.recordSuccess(email);
    }
}
