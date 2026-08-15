/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.robert.translation.storage;

/**
 * Magazyn plików użytkowników, adresowany kluczem.
 *
 * DLACZEGO INTERFEJS, skoro projekt usuwa interfejsy z jedną implementacją: implementacje
 * są DWIE i obie są używane. S3ObjectStore obsługuje MinIO i AWS, a InMemoryObjectStore
 * trzyma suite testową hermetyczną - bez niego 195 testów wymagałoby stojącego magazynu
 * obiektowego, tak samo jak wymagałoby serwera SMTP, gdyby nie Mailpit, i konta u dostawcy,
 * gdyby nie EchoTranslationProvider. To ta sama sytuacja co BucketProvider.
 *
 * KLUCZ, NIGDY URL. W bazie zapisujemy to, czym operuje ten interfejs, bo URL niesie
 * bucket, region, endpoint i schemat - a każde z nich zmienia się przy przenosinach do
 * innego dostawcy, postawieniu CDN-a albo zwykłej różnicy dev/prod. Adres wylicza się
 * z klucza przy odczycie; z URL-a zapisanego w bazie nie da się odzyskać niczego.
 *
 * Klucze buduje wyłącznie ObjectKeys i NIGDY nie powstają z danych przysłanych przez
 * klienta - nazwa pliku od użytkownika jedzie tylko do nagłówka Content-Disposition.
 *
 * Wszystkie metody rzucają ObjectStoreException przy awarii magazynu; brakujący obiekt
 * to osobny ObjectMissingException, bo to nie jest awaria, tylko stan do pokazania
 * użytkownikowi.
 */
public interface ObjectStore {

    /**
     * Zapisuje obiekt. Nadpisuje, jeśli klucz był zajęty - klucze niosą UUID zlecenia,
     * więc kolizja oznacza ponowienie tego samego zlecenia, a nie cudzy plik.
     */
    void put(String key, byte[] content, String contentType);

    /** Cała zawartość obiektu. Do wywołania dostawcy, gdzie i tak potrzebujemy całości. */
    byte[] read(String key);

    /**
     * Otwiera obiekt do strumieniowania. Wołający MUSI zamknąć wynik - stąd AutoCloseable
     * na StoredObject. Używane przy pobieraniu, żeby treść pliku nie lądowała w całości
     * na stercie tylko po to, by zaraz pojechać do gniazda.
     */
    StoredObject open(String key);

    /**
     * Kopiuje obiekt po stronie magazynu, BEZ przepuszczania bajtów przez aplikację.
     *
     * Tędy realizuje się trafienie w deduplikację: nowe zlecenie dostaje własną kopię wyniku
     * pod własnym prefiksem, więc zachowuje wyłączność na swoje obiekty i skasowanie go
     * nie rusza niczyich cudzych danych. Alternatywa - wskazanie dwóch zleceń na jeden
     * obiekt - wymagałaby liczenia referencji, czyli mechanizmu, którego pomyłka kasuje
     * plik wciąż używany.
     */
    void copy(String sourceKey, String targetKey);

    /**
     * Kasuje wszystko pod prefiksem. Zlecenie ma wyłączność na swój prefiks, więc to jest
     * całe kasowanie plików zlecenia - bez wyliczania, które obiekty do niego należą.
     */
    void deletePrefix(String prefix);

    boolean exists(String key);
}
