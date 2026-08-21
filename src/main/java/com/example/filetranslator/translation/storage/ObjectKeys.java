/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.filetranslator.translation.storage;

import java.util.UUID;

/**
 * Buduje klucze obiektów w magazynie - jedyne miejsce, w którym klucz powstaje.
 *
 * Układ klucza obejmuje identyfikator użytkownika, identyfikator zlecenia i nazwę pliku
 * źródłowego albo wynikowego wraz z rozszerzeniem.
 *
 * Klucz jest przypisany do zlecenia, a nie wyprowadzony z treści pliku. Adresowanie treścią
 * byłoby oszczędniejsze, bo trafienie w deduplikację nie wymagałoby kopiowania, ale wtedy dwa
 * zlecenia wskazywałyby jeden obiekt i skasowanie jednego musiałoby sprawdzać, czy drugie go
 * jeszcze nie potrzebuje. Jest to liczenie referencji, czyli mechanizm, którego pojedyncza
 * pomyłka kasuje plik będący w użyciu. Przy kluczu na zlecenie każde ma wyłączność na swój
 * prefiks, więc kasowanie jest jednym wywołaniem i nie ma czego policzyć źle.
 *
 * Identyfikator magazynowy jest losowy, a nie wzięty z identyfikatora wiersza, ponieważ obiekt
 * zapisywany jest przed wstawieniem wiersza - klucz zbudowany z identyfikatora wiersza wymagałby
 * odwrotnej kolejności.
 *
 * Prefiks użytkownika utrzymuje izolację, na której opiera się zasięg deduplikacji, i pozwala
 * skasować wszystkie pliki konta jednym wywołaniem. Nie jest natomiast zabezpieczeniem - dostępu
 * do cudzego pliku pilnuje warunek na identyfikator właściciela w zapytaniu, a nie kształt klucza.
 *
 * Nic w kluczu nie pochodzi od klienta: nazwa pliku przysłana przez użytkownika trafia wyłącznie
 * do nagłówka pobierania, a tutaj wchodzą identyfikator użytkownika, losowy identyfikator
 * i rozszerzenie z zamkniętego zbioru obsługiwanych typów.
 */
public final class ObjectKeys {

    private static final String SOURCE = "source";
    private static final String RESULT = "result";

    private ObjectKeys() {
    }

    /** Nowy identyfikator magazynowy zlecenia - znany przed zapisem obiektu i przed wierszem. */
    public static String newStorageId() {
        return UUID.randomUUID().toString();
    }

    /** Prefiks pojedynczego zlecenia, zakończony ukośnikiem, bo jest prefiksem, a nie kluczem. */
    public static String jobPrefix(Long userId, String storageId) {
        return "users/" + userId + "/jobs/" + storageId + "/";
    }

    /**
     * Prefiks obejmujący wszystkie pliki jednego użytkownika - do skasowania razem z kontem.
     *
     * Działa dzięki temu, że identyfikator użytkownika stoi w kluczu najwyżej. Gdyby prefiks
     * zaczynał się od zlecenia, usunięcie konta wymagałoby wypisania wszystkich jego kluczy
     * z bazy, czyli odczytu z wierszy, które przy kasowaniu konta i tak zaraz znikają.
     */
    public static String userPrefix(Long userId) {
        return "users/" + userId + "/";
    }

    public static String sourceKey(String jobPrefix, String extension) {
        return jobPrefix + SOURCE + extension;
    }

    public static String resultKey(String jobPrefix, String extension) {
        return jobPrefix + RESULT + extension;
    }

    /**
     * Wyprowadza prefiks zlecenia z jego klucza - do skasowania wszystkich jego obiektów.
     *
     * Wyprowadzanie zamiast przechowywania w osobnej kolumnie oznacza jedną regułę zamiast
     * trzeciej kolumny, którą trzeba wypełniać i która może rozjechać się z dwiema pozostałymi.
     */
    public static String prefixOf(String key) {
        int lastSlash = key.lastIndexOf('/');
        if (lastSlash < 0) {
            // Klucz bez ukośnika nie powstał w tej klasie, a kasowanie po takim prefiksie objęłoby
            // cały kubełek - dlatego zamiast zgadywać, metoda odmawia.
            throw new IllegalArgumentException("Klucz obiektu nie ma prefiksu zlecenia");
        }
        return key.substring(0, lastSlash + 1);
    }
}
