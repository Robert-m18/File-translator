/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.filetranslator.common.web;

import java.time.Instant;

/**
 * Odpowiedź na operację, która się udała, ale nie ma czego zwrócić poza potwierdzeniem.
 *
 * Znacznik czasu jest punktem na osi czasu, a nie czasem lokalnym: w odpowiedzi JSON niesie
 * informację o strefie. Obowiązuje tu ten sam powód co przy kolumnach czasowych w bazie -
 * czas bez strefy nie mówi, czyj to zegar, więc odbiorca nie ma jak przeliczyć go na czas
 * lokalny użytkownika i musiałby zgadywać strefę serwera. Przy punkcie na osi czasu
 * przeglądarka renderuje godzinę lokalną samodzielnie.
 *
 * To samo pole i z tego samego powodu niesie ApiProblem po stronie błędów.
 */
public record SuccessMessage(String message, Instant timestamp) {
}
