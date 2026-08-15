/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.robert.user.dto;

import com.example.robert.user.model.Role;

import java.time.Instant;

/**
 * Konto widziane przez administratora w panelu.
 *
 * BUDOWANY WYŁĄCZNIE PROJEKCJĄ JPQL (UserRepository.findAdminViews / findAdminView),
 * nigdy z encji. To nie jest kwestia wydajności, tylko tego, czego w tym rekordzie NIE MA:
 * encja User niesie hash hasła i jest jednocześnie UserDetails, więc zwrócona z kontrolera
 * wypuściłaby hash do API. Skoro encja nigdy nie opuszcza repozytorium, nie ma jak wyciec.
 * Pilnuje tego AdminPanelTest.list_shouldNotExposePasswordHash.
 *
 * JEDEN REKORD DLA LISTY, SZCZEGÓŁÓW I WSZYSTKICH CZTERECH AKCJI. Każda akcja oddaje
 * konto po zmianie, więc front podmienia jeden wiersz zamiast przeładowywać stronę -
 * i nie ma drugiego kształtu odpowiedzi, który mógłby się rozjechać z pierwszym.
 *
 * Adres email jest tu świadomie. Zasada "nie zdradzamy, które adresy są zarejestrowane"
 * chroni przepływy NIEUWIERZYTELNIONE (rejestracja, logowanie, reset hasła); tutaj wołający
 * udowodnił rolę osiągalną wyłącznie przez AdminBootstrap. Bez zmian zostaje natomiast
 * zakaz logowania adresów - do logu idzie samo id.
 *
 * Trzy pola o blokadach, bo to trzy różne stany i administrator musi je rozróżnić:
 * blockedAt/blockedReason to jego własna kara, a failedLoginAttempts/lockedUntil to
 * automatyczna blokada po nieudanych logowaniach, która zejdzie sama.
 */
public record AdminUserView(
        Long id,
        String name,
        String email,
        Role role,
        Instant createdAt,
        Instant blockedAt,
        String blockedReason,
        int failedLoginAttempts,
        Instant lockedUntil) {
}
