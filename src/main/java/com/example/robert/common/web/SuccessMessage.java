/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.robert.common.web;

import java.time.Instant;

/**
 * Odpowiedź na operację, która się udała, ale nie ma czego zwrócić poza potwierdzeniem.
 *
 * timestamp jest typu Instant, nie LocalDateTime, i to jest zmiana kontraktu: w JSON-ie
 * wychodzi teraz "2026-08-09T09:34:27.758Z" zamiast "2026-08-09T09:34:27.758". Ten sam
 * powód co przy kolumnach w bazie - czas bez strefy nie mówi, czyj to zegar, więc odbiorca
 * nie ma jak przeliczyć go na czas lokalny użytkownika i musi zgadywać strefę serwera.
 * Z Instantem przeglądarka renderuje lokalną godzinę sama (new Date(...) w JS).
 *
 * To samo pole i z tego samego powodu niesie ApiProblem po stronie błędów.
 */
public record SuccessMessage(String message, Instant timestamp) {
}
