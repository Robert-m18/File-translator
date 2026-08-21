/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.filetranslator.translation.storage;

/**
 * Magazyn obiektowy nie odpowiedział albo odmówił wykonania operacji.
 *
 * Wyjątek świadomie nie ma własnego handlera: jest to awaria infrastruktury, a nie stan
 * dziedzinowy, więc należy mu się ogólna odpowiedź o błędzie serwera i wpis w logu. Frontendowi
 * nie ma tu czego zaproponować, bo ponowienie niczego nie zmieni, dopóki magazyn nie wróci.
 *
 * W wykonawcy kolejki wpada w gałąź nieznanego błędu, traktowaną jako przejściowa, i tak ma być:
 * niedostępny magazyn to awaria mijająca, a porzucenie zlecenia po pierwszej takiej porażce
 * byłoby nieodwracalne.
 *
 * Komunikat nie zawiera klucza obiektu, ponieważ klucz niesie identyfikator użytkownika, a ten
 * wyjątek trafia do logu. Do korelacji służą identyfikator zlecenia i identyfikator żądania.
 */
public class ObjectStoreException extends RuntimeException {

    public ObjectStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
