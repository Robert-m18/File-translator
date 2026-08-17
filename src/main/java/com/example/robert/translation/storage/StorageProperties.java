/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.robert.translation.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Ustawienia magazynu obiektowego (prefiks app.storage).
 *
 * CELOWO BEZ @Validated, i to jest dokładnie ta sama decyzja co przy AdminProperties.
 * BindValidationException składa komunikat z FieldError.toString(), a tam siedzi
 * rejectedValue - więc walidacja pola, które niesie SEKRET, wypisałaby ten sekret
 * do raportu błędu startu, czyli do konsoli i do zbieracza logów. Tu takimi polami są
 * accessKey i secretKey.
 *
 * Sprawdzenie kompletności konfiguracji siedzi zamiast tego w S3ClientConfig.requireComplete:
 * buduje komunikat wyłącznie z NAZW brakujących pól i wykonuje się tylko wtedy, gdy magazyn
 * S3 jest w ogóle włączony. Wariant w pamięci nie potrzebuje żadnego z tych ustawień
 * i nie ma powodu, żeby go do nich zmuszać.
 *
 * @param type      memory - mapa w pamięci procesu (testy na profilu -Ph2, start bez magazynu);
 *                  s3 - prawdziwy magazyn obiektowy (MinIO lokalnie, S3 na produkcji)
 * @param endpoint  adres usługi zgodnej z S3. PUSTY oznacza prawdziwe AWS - wtedy adres
 *                  wylicza SDK z regionu i nazwy kubełka. Dla MinIO trzeba go podać.
 * @param bucket    nazwa kubełka; jeden na całą aplikację, rozdział na użytkowników
 *                  robią prefiksy kluczy (ObjectKeys)
 * @param region    wymagany przez SDK do podpisywania żądań NAWET wtedy, gdy po drugiej
 *                  stronie stoi MinIO, któremu region jest obojętny - bez niego klient
 *                  nie da się zbudować
 * @param accessKey poświadczenia podawane wprost. Świadomie nie sięgamy po domyślny łańcuch
 *                  dostawców AWS: na maszynie deweloperskiej podłapałby ~/.aws/credentials
 *                  i aplikacja pisałaby do CUDZEGO kubełka, wyglądając przy tym poprawnie
 * @param secretKey jak wyżej; NIGDY nie może trafić do logu ani do komunikatu wyjątku
 */
@ConfigurationProperties(prefix = "app.storage")
public record StorageProperties(
        Type type,
        String endpoint,
        String bucket,
        String region,
        String accessKey,
        String secretKey
) {

    public enum Type {
        /** Mapa w pamięci procesu. Pozwala uruchomić aplikację bez magazynu - rola Mailpita i atrapy ECHO. */
        MEMORY,
        /** Magazyn zgodny z S3: MinIO z docker compose albo prawdziwe AWS S3. */
        S3
    }
}
