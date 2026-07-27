/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.robert.notification.model;

/**
 * Rodzaje maili transakcyjnych.
 *
 * Enum, a nie goły String w kolumnie: publisher rozgałęzia się na tej wartości przy
 * wysyłce, więc kompilator ma pilnować, że każdy rodzaj ma obsługę. Literówka w nazwie
 * szablonu wychodziłaby inaczej dopiero w produkcji, przy pierwszym mailu danego typu.
 *
 * @param templateName nazwa pliku szablonu Thymeleaf w src/main/resources/templates
 */
public enum MailTemplate {

    VERIFICATION("email"),
    PASSWORD_RESET("password-reset"),
    ACCOUNT_EXISTS("account-exists");

    private final String templateName;

    MailTemplate(String templateName) {
        this.templateName = templateName;
    }

    public String templateName() {
        return templateName;
    }
}
