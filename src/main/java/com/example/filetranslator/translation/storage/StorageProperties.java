/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.filetranslator.translation.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Ustawienia magazynu obiektowego (prefiks app.storage).
 *
 * Typ świadomie nie jest walidowany adnotacją. Wyjątek zgłaszany przy nieudanym wiązaniu składa
 * komunikat z opisu błędnego pola, a ten zawiera odrzuconą wartość - walidacja pola niosącego
 * sekret wypisałaby go do raportu błędu startu, czyli do konsoli i do kolektora logów.
 *
 * Kontrola kompletności konfiguracji znajduje się zamiast tego w konfiguracji klienta: buduje
 * komunikat wyłącznie z nazw brakujących pól i wykonuje się tylko wtedy, gdy magazyn zewnętrzny
 * jest w ogóle włączony. Wariant w pamięci nie potrzebuje żadnego z tych ustawień.
 *
 * @param type      rodzaj magazynu: pamięć procesu albo usługa zgodna z S3
 * @param endpoint  adres usługi zgodnej z S3; wartość pusta oznacza prawdziwe AWS, gdzie adres
 *                  wylicza SDK z regionu i nazwy kubełka
 * @param bucket    nazwa kubełka; jeden na całą aplikację, a rozdział na użytkowników realizują
 *                  prefiksy kluczy
 * @param region    wymagany przez SDK do podpisywania żądań również wtedy, gdy po drugiej stronie
 *                  stoi usługa, której region jest obojętny - bez niego klienta nie da się zbudować
 * @param accessKey poświadczenia podawane wprost; domyślny łańcuch dostawców tożsamości
 *                  podłapałby na maszynie deweloperskiej lokalną konfigurację i aplikacja pisałaby
 *                  do cudzego kubełka, wyglądając przy tym na działającą poprawnie
 * @param secretKey jak wyżej; nigdy nie trafia do logu ani do komunikatu wyjątku
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
        /** Mapa w pamięci procesu - pozwala uruchomić aplikację bez zewnętrznego magazynu. */
        MEMORY,
        /** Magazyn zgodny z S3: MinIO uruchomione lokalnie albo usługa chmurowa. */
        S3
    }
}
