/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.filetranslator.translation.storage;

/**
 * Wiersz zlecenia istnieje, ale jego obiektu nie ma w magazynie.
 *
 * Nie jest to awaria i dlatego ma własny typ: taki stan jest przewidzianą konsekwencją tego, że
 * retencja działa w dwóch miejscach - wiersze kasuje zadanie aplikacji, a obiekty reguła
 * wygasania na kubełku. Gdy te dwie wartości się rozjadą, a wiąże je wyłącznie uważność, obiekt
 * może zniknąć wcześniej niż opisujący go wiersz.
 *
 * Osobny wyjątek nadaje temu rozjazdowi rozpoznawalny objaw: użytkownik dostaje czytelną
 * odpowiedź o wygaśnięciu pliku zamiast błędu serwera, a w logu zostaje ślad pozwalający poznać,
 * że reguła na kubełku kasuje za wcześnie.
 */
public class ObjectMissingException extends RuntimeException {

    public ObjectMissingException(String message) {
        super(message);
    }
}
