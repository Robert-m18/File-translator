/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.filetranslator.translation.storage;

/**
 * Port magazynu plików użytkowników, adresowanego kluczem.
 *
 * Interfejs istnieje, ponieważ implementacje są dwie i obie są używane: wariant zgodny z S3
 * obsługuje MinIO lokalnie i magazyn chmurowy na produkcji, a wariant w pamięci pozwala
 * uruchomić aplikację i testy bez stojącego magazynu. Jest to ta sama rola, którą dla poczty
 * pełni lokalny serwer testowy, a dla tłumaczenia atrapa dostawcy.
 *
 * Operacje posługują się kluczem, nigdy adresem URL. Adres niesie nazwę kubełka, region,
 * punkt końcowy i schemat, a każde z nich zmienia się przy przenosinach do innego dostawcy,
 * postawieniu sieci dostarczania treści albo zwykłej różnicy między środowiskami. Adres wylicza
 * się z klucza przy odczycie, natomiast z adresu zapisanego w bazie nie da się odzyskać niczego.
 *
 * Klucze buduje wyłącznie klasa pomocnicza tego pakietu i nigdy nie powstają z danych
 * przysłanych przez klienta.
 *
 * Awaria magazynu sygnalizowana jest wyjątkiem awarii, a brakujący obiekt osobnym wyjątkiem,
 * ponieważ nie jest awarią, tylko stanem do pokazania użytkownikowi.
 */
public interface ObjectStore {

    /**
     * Zapisuje obiekt, nadpisując istniejący pod tym samym kluczem. Klucze niosą losowy
     * identyfikator zlecenia, więc kolizja oznacza ponowienie tego samego zlecenia, a nie cudzy
     * plik.
     */
    void put(String key, byte[] content, String contentType);

    /** Zwraca całą zawartość obiektu - używane przy wywołaniu dostawcy, który i tak potrzebuje całości. */
    byte[] read(String key);

    /**
     * Otwiera obiekt do strumieniowania; wołający musi zamknąć wynik.
     *
     * Używane przy pobieraniu, żeby treść pliku nie lądowała w całości na stercie tylko po to,
     * by zaraz trafić do gniazda - przy równoczesnych pobraniach zużycie pamięci mnożyłoby się
     * przez ich liczbę.
     */
    StoredObject open(String key);

    /**
     * Kopiuje obiekt po stronie magazynu, bez przepuszczania bajtów przez aplikację.
     *
     * Tą drogą realizowane jest trafienie w deduplikację: nowe zlecenie dostaje własną kopię
     * wyniku pod własnym prefiksem, więc zachowuje wyłączność na swoje obiekty i jego skasowanie
     * nie rusza cudzych danych. Wskazanie dwóch zleceń na jeden obiekt wymagałoby liczenia
     * referencji, czyli mechanizmu, którego pomyłka kasuje plik będący w użyciu.
     */
    void copy(String sourceKey, String targetKey);

    /**
     * Kasuje wszystko pod podanym prefiksem. Zlecenie ma wyłączność na swój prefiks, więc jest to
     * całe kasowanie jego plików - bez wyliczania, które obiekty do niego należą.
     */
    void deletePrefix(String prefix);

    boolean exists(String key);
}
