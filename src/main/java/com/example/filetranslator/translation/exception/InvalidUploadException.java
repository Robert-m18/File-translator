/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.filetranslator.translation.exception;

import lombok.Getter;

/**
 * Przesłany plik nie nadaje się do tłumaczenia.
 *
 * Jedna klasa na kilka przyczyn (pusty, złe rozszerzenie, złe kodowanie), bo wszystkie
 * kończą się tą samą odpowiedzią 400 i różnią się wyłącznie kodem. Osobne klasy byłyby
 * trzema identycznymi plikami i trzema identycznymi handlerami.
 *
 * Wyjątek NIE zna kodu HTTP - mapowanie siedzi w GlobalExceptionHandler, tak jak w całym
 * projekcie. Niesie tylko stabilny kod maszynowy dla frontendu.
 */
@Getter
public class InvalidUploadException extends RuntimeException {

    private final String code;

    public InvalidUploadException(String code, String message) {
        super(message);
        this.code = code;
    }
}
