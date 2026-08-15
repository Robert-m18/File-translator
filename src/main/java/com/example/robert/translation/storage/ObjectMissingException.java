/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.robert.translation.storage;

/**
 * Wiersz zlecenia istnieje, ale jego obiektu nie ma w magazynie.
 *
 * To NIE jest awaria i dlatego ma własny typ: taki stan jest przewidzianą konsekwencją
 * tego, że retencja żyje teraz w DWÓCH miejscach. Wiersze kasuje TranslationCleanupJob
 * według app.translation.retention, a obiekty - reguła wygasania na kubełku. Jeśli te dwie
 * wartości się rozjadą (a nic ich nie wiąże poza uważnością), obiekt może zniknąć wcześniej
 * niż opisujący go wiersz.
 *
 * Osobny wyjątek jest tu po to, żeby ten rozjazd MIAŁ OBJAW: użytkownik dostaje
 * 410 CONTENT_EXPIRED z czytelnym komunikatem zamiast 500, a w logu zostaje ślad, po którym
 * da się poznać, że reguła kubełka kasuje za wcześnie. Bez tego typu rozjazd konfiguracji
 * objawiałby się serią błędów serwera bez wskazówki, skąd pochodzą.
 */
public class ObjectMissingException extends RuntimeException {

    public ObjectMissingException(String message) {
        super(message);
    }
}
