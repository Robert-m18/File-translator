/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.robert.auth.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Zgłoszenie rejestracji czekające na potwierdzenie adresu email.
 *
 * Nie jest to encja użytkownika i nie ma z nią relacji - wiersz w users powstaje dopiero
 * przy potwierdzeniu (AuthService.confirmEmail). Do tego czasu adres nie jest zajęty,
 * więc porzucone zgłoszenie nie blokuje rejestracji właścicielowi skrzynki.
 *
 * KLUCZOWE: kolumna email NIE jest unikalna. Kilka równoległych zgłoszeń na ten sam adres
 * to poprawny stan, a nie usterka - każde niesie własny token i własny hash hasła.
 * Dzięki temu obce zgłoszenie nie może podmienić hasła w zgłoszeniu prawowitego
 * użytkownika: potwierdzenie aktywuje dokładnie to zgłoszenie, którego token przyszedł
 * w klikniętym linku.
 */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "pending_registrations")
public class PendingRegistration {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 255)
    private String email;

    @Column(nullable = false, length = 50)
    private String name;

    /** Hasło zahashowane BCryptem już tutaj - w poczekalni nigdy nie leży hasło jawnie. */
    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    public PendingRegistration(String email, String name, String passwordHash,
                               String tokenHash, LocalDateTime createdAt, LocalDateTime expiresAt) {
        this.email = email;
        this.name = name;
        this.passwordHash = passwordHash;
        this.tokenHash = tokenHash;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public boolean isExpired() {
        return expiresAt.isBefore(LocalDateTime.now());
    }
}
