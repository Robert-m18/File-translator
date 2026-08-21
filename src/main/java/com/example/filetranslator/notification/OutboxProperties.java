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

        /** Ile wiadomości pobierać na jeden cykl. */
        @Positive int batchSize,

        /**
         * Ile wiadomości z jednej paczki wysyłać równolegle.
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
         * Jak długo przechowywane są wysłane wiadomości, licząc od chwili wysłania.
         *
         * Powodem nie jest rozmiar tabeli, tylko zawartość wierszy: ładunek wiadomości niesie
         * surowy token potwierdzenia adresu albo resetu hasła, a pole odbiorcy jego adres.
         * Poczekalnia rejestracyjna przechowuje wyłącznie skrót tokenu, żeby odczyt bazy nie
         * dawał użytecznego sekretu, więc bezterminowo przechowywany wiersz skrzynki nadawczej
         * znosiłby sens tego zabiegu.
         *
         * Wartość jest wyprowadzona z ważności tokenów: najdłuższy z nich wygasa po dobie, więc
         * po tym czasie ładunek nie niesie już niczego, czym dałoby się posłużyć. Dłuższe
         * przechowywanie wydłuża wyłącznie okno ekspozycji jawnych sekretów, a do historii
         * wysyłek służy log.
         */
        @NotNull Duration retention
) {
}
