/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.robert.notification;

import com.example.robert.auth.UserRegisteredEvent;
import com.example.robert.notification.EmailService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Nasłuchuje na UserRegisteredEvent i wysyła mail weryfikacyjny.
 * AFTER_COMMIT - jeśli transakcja rejestracji się wywali (np. deadlock, inny błąd),
 * event w ogóle nie zostanie odpalony i mail nie poleci do niepotwierdzonego w bazie usera.
 */
@Component
@RequiredArgsConstructor
public class UserRegisteredEventListener {

    private final EmailService emailService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onUserRegistered(UserRegisteredEvent event) {
        emailService.sendVerificationEmail(event.email(), event.name(), event.verificationToken());
    }
}