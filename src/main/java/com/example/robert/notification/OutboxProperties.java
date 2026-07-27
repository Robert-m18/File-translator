/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.robert.notification;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Ustawienia skrzynki nadawczej maili (prefiks app.outbox).
 */
@Validated
@ConfigurationProperties(prefix = "app.outbox")
public record OutboxProperties(

        /**
         * Czy cykliczne wysyłanie jest włączone. Na testach wyłączone: publisher odpalający
         * się w tle zabierałby wiersze, które testy chcą sprawdzić, i wyniki byłyby zależne
         * od tego, co zdąży się wykonać.
         */
        boolean enabled,

        /** Ile wiadomości bierzemy na jeden cykl. */
        @Positive int batchSize,

        /** Po tylu nieudanych podejściach wiadomość dostaje status FAILED. */
        @Positive int maxAttempts,

        /**
         * Podstawa odstępu między próbami. Rośnie wykładniczo z liczbą podejść, więc
         * chwilowa awaria SMTP nie zamienia się w odpytywanie serwera co sekundę.
         */
        @NotNull Duration retryBackoff,

        /**
         * Na jak długo instancja rezerwuje wiersz, zabierając się do wysyłki. Musi być
         * wyraźnie dłuższe niż realny czas wysyłki maila, inaczej druga instancja
         * zabrałaby ten sam wiersz, gdy pierwsza jeszcze czeka na SMTP.
         */
        @NotNull Duration claimTimeout
) {
}
