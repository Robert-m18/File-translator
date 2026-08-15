/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.robert.admin.exception;

import lombok.Getter;

/**
 * Akcja panelu jest sama w sobie poprawna, ale nie wolno jej wykonać na TYM koncie.
 *
 * Jedna klasa na kilka przyczyn (CANNOT_BLOCK_SELF, LAST_ADMIN_CANNOT_BE_BLOCKED), bo
 * wszystkie kończą się tą samą odpowiedzią 409 i różnią się wyłącznie kodem - ten sam
 * wzorzec co InvalidUploadException. Wyjątek NIE zna kodu HTTP; mapowanie siedzi
 * w GlobalExceptionHandler.
 */
@Getter
public class AdminActionRejectedException extends RuntimeException {

    private final String code;

    public AdminActionRejectedException(String code, String message) {
        super(message);
        this.code = code;
    }
}
