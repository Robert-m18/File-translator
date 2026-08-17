/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.filetranslator.translation.provider;

/**
 * Uchwyt do dokumentu wgranego u dostawcy - wszystko, czego trzeba, żeby wrócić po wynik.
 *
 * ZAPISYWANY W WIERSZU ZLECENIA, i to jest jedyny powód, dla którego port tłumaczenia
 * dokumentów jest rozbity na trzy operacje zamiast jednej blokującej. Tłumaczenie dokumentu
 * po stronie dostawcy jest asynchroniczne i może trwać dłużej niż okno rezerwacji zlecenia
 * (app.translation.claim-timeout). Bez zapisanego uchwytu kolejna rezerwacja wgrałaby ten sam
 * dokument DRUGI RAZ i zapłaciła za niego drugi raz; z uchwytem wraca do odpytywania tam,
 * gdzie poprzednia skończyła.
 *
 * documentKey to klucz szyfrujący wystawiony przez dostawcę przy wgraniu - bez niego nie da
 * się ani sprawdzić statusu, ani pobrać wyniku. Traktujemy go jak sekret: nie trafia do logu.
 */
public record DocumentHandle(String documentId, String documentKey) {
}
