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
 * Magazyn w pamięci procesu. Domyślna implementacja.
 *
 * PO CO ISTNIEJE: żeby aplikacja i testy nie wymagały stojącego magazynu obiektowego.
 * Dokładnie ta sama rola co Mailpit dla poczty i EchoTranslationProvider dla tłumaczenia -
 * lokalne zastępstwo, dzięki któremu `docker compose up` jest działającym systemem bez
 * konta u dostawcy. W testach obsługuje już tylko wariant `./mvnw test -Ph2` (bez Dockera):
 * domyślny przebieg idzie na MinIO w kontenerze, więc S3ObjectStore wykonuje się naprawdę.
 *
 * NIE NADAJE SIĘ DO NICZEGO POZA TYM i nie udaje, że się nadaje: zawartość ginie przy
 * restarcie, nie jest współdzielona między instancjami i rośnie bez ograniczenia. Przy dwóch
 * instancjach użytkownik dostawałby "pliku nie ma" w zależności od tego, którą trafi -
 * dlatego prawdziwe wdrożenie ustawia app.storage.type=s3.
 *
 * Ostrzeżenie przy starcie jest tu z tego samego powodu, dla którego ma je atrapa tłumacza:
 * gdy ktoś zapomni ustawić zmiennej, objawem jest poprawnie działająca aplikacja, która
 * po restarcie zgubiła wszystkie pliki. Jedna linia w logu rozstrzyga to w sekundę.
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
        // Kopia tablicy, nie referencja: wołający może swoją zmodyfikować, a magazyn ma
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
            // Bez klucza w komunikacie - niesie identyfikator użytkownika, a komunikat
            // trafia do logu. Ten sam powód co przy ObjectStoreException.
            throw new ObjectMissingException("Pliku nie ma już w magazynie");
        }
        return stored;
    }
}
