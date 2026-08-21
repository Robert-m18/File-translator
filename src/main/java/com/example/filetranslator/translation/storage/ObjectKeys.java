/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.filetranslator.translation.storage;

import java.util.UUID;

/**
 * Budowa kluczy obiektów. JEDYNE miejsce, w którym powstaje klucz.
 *
 * UKŁAD: users/{userId}/jobs/{storageId}/source.{ext} oraz .../result.{ext}
 *
 * KLUCZ PER ZLECENIE, a nie adresowanie treścią (klucz = SHA-256 pliku). Adresowanie treścią
 * byłoby oszczędniejsze - trafienie w deduplikację nie kopiowałoby nic, bo obiekt już leżałby
 * pod właściwym kluczem - ale wtedy dwa zlecenia wskazują JEDEN obiekt i skasowanie jednego
 * musi sprawdzić, czy drugie go jeszcze nie potrzebuje. To jest liczenie referencji, czyli
 * mechanizm, którego jedna pomyłka kasuje komuś plik w użyciu. Tutaj każde zlecenie ma
 * wyłączność na swój prefiks, więc kasowanie jest jednym wywołaniem i nie ma czego policzyć źle.
 *
 * DLACZEGO storageId TO UUID, A NIE job.id: obiekt musi zostać zapisany PRZED wstawieniem
 * wiersza (uzasadnienie kolejności przy TranslationService.submit), a klucz z identyfikatora
 * wiersza wymagałby najpierw wstawienia wiersza - czyli dokładnie odwrotnej kolejności.
 * UUID jest znany przed jednym i drugim.
 *
 * Prefiks users/{userId}/ nie jest ozdobą: utrzymuje izolację per użytkownik, tę samą,
 * na której stoi zasięg deduplikacji, i pozwala nadać na kubełku politykę per użytkownik,
 * gdyby kiedyś była potrzebna. Nie jest natomiast ŻADNYM zabezpieczeniem - dostępu do cudzego
 * pliku pilnuje warunek na user_id w zapytaniu, a nie kształt klucza.
 *
 * NIC W KLUCZU NIE POCHODZI OD KLIENTA. Nazwa pliku przysłana przez użytkownika jedzie
 * wyłącznie do nagłówka Content-Disposition; tutaj wchodzi identyfikator użytkownika, UUID
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

    /** users/{userId}/jobs/{storageId}/ - z ukośnikiem na końcu, bo to prefiks, nie klucz. */
    public static String jobPrefix(Long userId, String storageId) {
        return "users/" + userId + "/jobs/" + storageId + "/";
    }

    /**
     * users/{userId}/ - wszystkie pliki jednego użytkownika, do skasowania razem z kontem.
     *
     * Działa wyłącznie dlatego, że identyfikator użytkownika stoi w kluczu NAJWYŻEJ, i to
     * jest właśnie ten "izolacyjny" powód wymieniony w opisie układu wyżej. Gdyby prefiks
     * zaczynał się od zlecenia, usunięcie konta wymagałoby wypisania wszystkich jego
     * kluczy z bazy - czyli odczytu, który przy kasowaniu konta i tak zaraz znika.
     *
     * Identyfikator jest liczbą z naszej bazy, nie danymi od klienta, więc nie ma tu czego
     * uciekać ani sprawdzać - w odróżnieniu od nazwy pliku, która do klucza nie trafia nigdy.
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
     * Prefiks zlecenia odczytany z jego klucza - do skasowania wszystkich jego obiektów.
     *
     * Wyprowadzany z klucza zamiast trzymany w osobnej kolumnie, bo to jedna reguła
     * ("wszystko do ostatniego ukośnika") zamiast trzeciej kolumny, którą trzeba wypełniać
     * i która może się rozjechać z dwiema pozostałymi.
     */
    public static String prefixOf(String key) {
        int lastSlash = key.lastIndexOf('/');
        if (lastSlash < 0) {
            // Klucz bez ukośnika nie powstał w tej klasie. Kasowanie po takim prefiksie
            // objęłoby cały kubełek, więc zamiast zgadywać - odmawiamy.
            throw new IllegalArgumentException("Klucz obiektu nie ma prefiksu zlecenia");
        }
        return key.substring(0, lastSlash + 1);
    }
}
