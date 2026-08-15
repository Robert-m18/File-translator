/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.robert.translation.storage;

/**
 * Magazyn obiektowy nie odpowiedział albo odmówił.
 *
 * Świadomie BEZ własnego handlera w GlobalExceptionHandler: to jest awaria infrastruktury,
 * a nie stan dziedzinowy, więc należy jej się ogólne 500 i wpis w logu. Frontendowi nie ma
 * tu czego zaproponować - ponowienie zlecenia niczego nie zmieni, dopóki magazyn nie wróci.
 *
 * W workerze wpada w gałąź "nieznany błąd", która jest traktowana jako PRZEJŚCIOWA -
 * i tak ma być: niedostępny magazyn to awaria mijająca, a porzucenie zlecenia po pierwszej
 * takiej porażce byłoby nieodwracalne.
 *
 * Komunikat NIE ZAWIERA klucza obiektu - klucz niesie identyfikator użytkownika, a ten
 * wyjątek trafia do logu. Do korelacji służy id zlecenia i traceId, jak wszędzie indziej.
 */
public class ObjectStoreException extends RuntimeException {

    public ObjectStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
