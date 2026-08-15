/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.robert.translation.provider;

import com.example.robert.translation.model.TargetLanguage;

/**
 * Źródło tłumaczenia tekstu - port do świata zewnętrznego.
 *
 * Wydzielone do interfejsu, bo druga implementacja REALNIE istnieje i jest używana, a nie
 * dlatego, że "tak się robi" (projekt usunął już interfejsy z jedną implementacją).
 * EchoTranslationProvider nie jest atrapą odłożoną na później: to on pozwala trzymać CI
 * hermetycznym i uruchomić cały przepływ na docker compose bez zakładania konta u dostawcy.
 * Wybór robi właściwość app.translation.provider - dokładnie tak jak przy BucketProvider.
 *
 * KONTRAKT BŁĘDÓW: implementacja rzuca TranslationProviderException i sama rozstrzyga, czy
 * porażka jest przejściowa. Worker nie zna ani protokołu dostawcy, ani jego kodów - dostaje
 * jedną informację: ponawiać czy się poddać. Bez tego rozróżnienia każdy błąd - także zły
 * klucz API czy nieobsługiwany język - przechodziłby przez pełny backoff i wyczerpanie prób,
 * czyli kilkanaście minut zwłoki, żeby dojść do wniosku znanego od pierwszej odpowiedzi.
 */
public interface TranslationProvider {

    /**
     * @param text   treść do przetłumaczenia (już zwalidowana: niepusta, poprawny UTF-8)
     * @param target język docelowy
     * @return przetłumaczony tekst wraz z wykrytym językiem źródła
     * @throws TranslationProviderException gdy dostawca odmówił albo nie odpowiedział
     */
    TranslationResult translate(String text, TargetLanguage target);

    /* ---------------------------------------------------------------------------------
     * Dokumenty (PDF, XLSX) - osobna, TRZYSTOPNIOWA ścieżka.
     *
     * DLACZEGO NIE JEDNA METODA translateDocument(bajty) BLOKUJĄCA DO SKUTKU: tłumaczenie
     * dokumentu po stronie dostawcy jest asynchroniczne i potrafi trwać dłużej niż okno
     * rezerwacji zlecenia. Metoda blokująca oznaczałaby albo wątek roboczy zajęty przez cały
     * ten czas, albo - po wygaśnięciu rezerwacji - wgranie tego samego dokumentu drugi raz
     * i zapłacenie za nie drugi raz. Rozbicie na trzy kroki pozwala zapisać uchwyt w wierszu
     * zlecenia i WRÓCIĆ do odpytywania przy następnej rezerwacji.
     *
     * Ceną jest to, że kształt protokołu dostawcy widać w porcie. Przyjęte świadomie:
     * alternatywą jest płacenie dwa razy za wolno tłumaczące się dokumenty.
     * --------------------------------------------------------------------------------- */

    /**
     * Wgrywa dokument i oddaje uchwyt do niego. NIE czeka na tłumaczenie.
     *
     * @param filename nazwa pliku - dostawca rozpoznaje po niej format, więc rozszerzenie
     *                 musi się zgadzać z zawartością
     */
    DocumentHandle uploadDocument(byte[] content, String filename, TargetLanguage target);

    /** Stan tłumaczenia. Wołane tyle razy, ile trzeba - nie zmienia niczego po stronie dostawcy. */
    DocumentStatus checkDocument(DocumentHandle handle);

    /**
     * Pobiera przetłumaczony dokument.
     *
     * UWAGA, POBRAĆ MOŻNA TYLKO RAZ - dostawca kasuje dokument po pobraniu. Wołający musi
     * więc zapisać wynik trwale, ZANIM uzna zlecenie za zakończone.
     *
     * @throws DocumentUnavailableException gdy dokumentu już nie ma (pobrany wcześniej albo
     *                                      wygasł u dostawcy) - trzeba wgrać go od nowa
     */
    byte[] downloadDocument(DocumentHandle handle);
}
