/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.robert.auth;

/**
 * Event publikowany po rejestracji użytkownika (po commicie transakcji).
 * Zawiera minimalne dane potrzebne do wysłania maila weryfikacyjnego.
 */
public record UserRegisteredEvent(Long userId, String email, String name, String verificationToken) {
}

