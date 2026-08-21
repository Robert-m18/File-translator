/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.filetranslator.translation.storage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Magazyn trzymający obiekty w pamięci procesu - implementacja domyślna.
 *
 * Istnieje po to, żeby aplikacja i testy nie wymagały stojącego magazynu obiektowego. Jest to ta
 * sama rola, którą dla poczty pełni lokalny serwer testowy, a dla tłumaczenia atrapa dostawcy:
 * uruchomienie środowiska nie wymaga konta u zewnętrznego dostawcy. W testach obsługuje wariant
 * bez Dockera, ponieważ domyślny przebieg korzysta z prawdziwego magazynu w kontenerze.
 *
 * Do zastosowań produkcyjnych się nie nadaje i tego nie ukrywa: zawartość ginie przy restarcie,
 * nie jest współdzielona między instancjami i rośnie bez ograniczenia. Przy dwóch instancjach
 * użytkownik dostawałby informację o braku pliku zależnie od tego, którą trafi.
 *
 * Ostrzeżenie przy starcie istnieje z tego samego powodu co przy atrapie tłumacza: gdy rodzaj
 * magazynu zostanie pominięty w konfiguracji, objawem jest poprawnie działająca aplikacja, która
 * po restarcie zgubiła wszystkie pliki. Jedna linia w logu rozstrzyga to natychmiast.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.storage", name = "type", havingValue = "memory", matchIfMissing = true)
public class InMemoryObjectStore implements ObjectStore {

    private static final String OCTET_STREAM = "application/octet-stream";

    private final Map<String, StoredBytes> objects = new ConcurrentHashMap<>();

    private record StoredBytes(byte[] content, String contentType) {
    }

    public InMemoryObjectStore() {
        log.warn("Magazyn plików: PAMIĘĆ PROCESU - pliki znikają przy restarcie "
                + "i nie są współdzielone między instancjami. Do prawdziwego użycia ustaw app.storage.type=s3");
    }

    @Override
    public void put(String key, byte[] content, String contentType) {
        // Kopia tablicy zamiast referencji: wołający może zmodyfikować swoją, a magazyn ma
        // zachowywać się jak magazyn, czyli oddawać to, co dostał w chwili zapisu.
        objects.put(key, new StoredBytes(content.clone(),
                contentType == null ? OCTET_STREAM : contentType));
    }

    @Override
    public byte[] read(String key) {
        return require(key).content().clone();
    }

    @Override
    public StoredObject open(String key) {
        StoredBytes stored = require(key);
        return new StoredObject(
                new ByteArrayInputStream(stored.content()),
                stored.content().length,
                stored.contentType());
    }

    @Override
    public void copy(String sourceKey, String targetKey) {
        StoredBytes stored = require(sourceKey);
        objects.put(targetKey, new StoredBytes(stored.content().clone(), stored.contentType()));
    }

    @Override
    public void deletePrefix(String prefix) {
        objects.keySet().removeIf(key -> key.startsWith(prefix));
    }

    @Override
    public boolean exists(String key) {
        return objects.containsKey(key);
    }

    private StoredBytes require(String key) {
        StoredBytes stored = objects.get(key);
        if (stored == null) {
            // Bez klucza w komunikacie - niesie on identyfikator użytkownika, a komunikat trafia
            // do logu.
            throw new ObjectMissingException("Pliku nie ma już w magazynie");
        }
        return stored;
    }
}
