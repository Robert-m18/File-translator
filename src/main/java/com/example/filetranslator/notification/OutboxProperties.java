/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.filetranslator.notification;

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

        /**
         * Ile maili z jednej paczki wysyłamy równolegle.
         *
         * Musi być ograniczone, i to jest tu cała trudność: serwery SMTP limitują liczbę
         * jednoczesnych połączeń z jednego adresu, a przekroczenie limitu kończy się
         * odrzuceniem albo czasowym zablokowaniem nadawcy. Wysyłka całej paczki naraz
         * zamieniłaby więc chwilowy ruch w awarię dostarczania.
         */
        @Positive int concurrency,

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
        @NotNull Duration claimTimeout,

        /**
         * Jak długo trzymamy wysłane wiadomości, licząc od chwili wysłania.
         *
         * Nie chodzi o rozmiar tabeli - indeks (status, next_retry_at) trzyma zapytanie
         * rezerwujące selektywnym niezależnie od tego, ile SENT-ów się nazbiera. Chodzi
         * o to, CO leży w tych wierszach: payload niesie SUROWY token weryfikacyjny albo
         * resetu hasła, a recipient adres email. Tabela zgłoszeń rejestracji trzyma tylko
         * SHA-256 tokenu właśnie po to, żeby odczyt bazy nie dawał użytecznego sekretu -
         * wysłany wiersz outboxu trzymany bezterminowo znosi sens tego hashowania.
         *
         * Doba jest wartością wyprowadzoną, nie zgadniętą: token rejestracji jest ważny
         * 24 h (najdłuższy z naszych; reset hasła to 1 h), więc po tym czasie payload
         * nie niesie już nic, czym można się posłużyć. Trzymanie wiersza dłużej wydłuża
         * wyłącznie okno ekspozycji plaintextu.
         *
         * Podnoszenie tego wymaga świadomej decyzji o retencji sekretów, a nie samego
         * "przydałaby się dłuższa historia wysyłek" - do historii służy log.
         */
        @NotNull Duration retention
) {
}
