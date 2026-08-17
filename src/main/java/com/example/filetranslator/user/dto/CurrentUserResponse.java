/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.filetranslator.user.dto;

/**
 * Dane zalogowanego użytkownika zwracane przez GET /auth/me.
 *
 * Zestaw pól jest świadomie wąski: to, co frontend musi wyświetlić (imię, email)
 * i po czym rozgałęzia widoki (rola), plus identyfikator do korelacji. Świadomie NIE ma
 * tu password, enabled, failedLoginAttempts ani lockedUntil - stan mechanizmów
 * bezpieczeństwa jest sprawą serwera i wyciekanie go do przeglądarki nie daje
 * użytkownikowi niczego, a napastnikowi mówi, jak blisko jest blokady konta.
 *
 * To nie jest przywrócenie skasowanego UserResponseDTO: tamto obsługiwało CRUD
 * administracyjny nad CUDZYMI kontami, ten rekord opisuje wyłącznie właściciela żądania.
 *
 * Rola jako String, nie enum Role: format na drucie jest kontraktem z frontendem
 * i nie może się zmienić przy refaktorze typu po stronie Javy.
 */
public record CurrentUserResponse(Long id, String name, String email, String role) {}
