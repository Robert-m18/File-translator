/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.robert.common.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.regex.Pattern;

/**
 * Custom validator dla emaila
 * Sprawdza format emaila zgodnie ze standardem RFC 5322 (uproszczona wersja)
 */
public class ValidEmailValidator implements ConstraintValidator<ValidEmail, String> {

    private static final String EMAIL_PATTERN =
            "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$";

    private static final Pattern pattern = Pattern.compile(EMAIL_PATTERN);

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return true; // @NotNull obsługuje null
        }

        if (value.trim().isEmpty()) {
            return false;
        }

        return pattern.matcher(value).matches();
    }
}

