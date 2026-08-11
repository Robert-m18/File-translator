/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.robert.translation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Ustawienia tłumaczenia plików (prefiks app.translation).
 */
@Validated
@ConfigurationProperties(prefix = "app.translation")
public record TranslationProperties(

        /**
         * Czy worker ma cyklicznie brać zlecenia z kolejki. Na testach wyłączone: worker
         * odpalający się w tle zabierałby wiersze, które testy chcą sprawdzić, i wynik
         * zależałby od tego, co zdąży się wykonać. Testy wołają processBatch() wprost.
         */
        boolean enabled,

        /** Który dostawca faktycznie tłumaczy. Szczegóły: TranslationProvider. */
        @NotNull Provider provider,

        /**
         * Ile znaków użytkownik może zlecić w ciągu doby.
         *
         * To nie jest ochrona serwera - od tego jest limiter żądań - tylko ochrona konta
         * u dostawcy. Darmowy próg DeepL liczy się dla CAŁEGO konta, więc jeden użytkownik
         * w pętli wyczerpuje go dla wszystkich pozostałych. Limitu na adres IP to nie
         * zatrzyma, bo zalogowany użytkownik zmienia adres bez utraty tożsamości.
         */
        @Positive int dailyCharLimit,

        /** Ile zleceń bierzemy na jeden cykl. */
        @Positive int batchSize,

        /**
         * Ile zleceń z jednej paczki tłumaczymy równolegle.
         *
         * Musi być ograniczone: dostawcy limitują liczbę równoczesnych żądań, a przekroczenie
         * kończy się odpowiedzią 429 dla całej paczki. Nieograniczona pula zamieniłaby
         * chwilowy ruch w serię ponowień.
         */
        @Positive int concurrency,

        /** Po tylu podejściach zlecenie dostaje status FAILED. */
        @Positive int maxAttempts,

        /** Podstawa odstępu między próbami. Rośnie wykładniczo z liczbą podejść. */
        @NotNull Duration retryBackoff,

        /**
         * Na jak długo instancja rezerwuje zlecenie, zabierając się do tłumaczenia. Musi być
         * wyraźnie dłuższe niż realny czas odpowiedzi dostawcy, inaczej druga instancja
         * weźmie to samo zlecenie, gdy pierwsza jeszcze czeka.
         */
        @NotNull Duration claimTimeout,

        /**
         * Jak długo trzymamy zlecenia, licząc od chwili utworzenia.
         *
         * Chodzi o treść, nie o rozmiar tabeli: w source_content i result_content leżą PLIKI
         * UŻYTKOWNIKA. Bezterminowe trzymanie ich znaczy, że wyciek bazy oddaje wszystko,
         * co ktokolwiek kiedykolwiek tłumaczył. Użytkownik może skasować zlecenie sam
         * (DELETE /translations/{id}); retencja jest tym, co dzieje się, gdy tego nie zrobi.
         */
        @NotNull Duration retention,

        @NotNull @Valid DeepL deepl
) {

    public enum Provider {
        /** Atrapa - oznacza tekst, nie tłumaczy. Testy i dev bez klucza. */
        ECHO,
        /** DeepL API. Wymaga app.translation.deepl.api-key. */
        DEEPL
    }

    /**
     * @param apiUrl         baza adresu API; darmowe konta mają INNY host niż płatne
     *                       (api-free.deepl.com vs api.deepl.com) - pomyłka daje 403
     * @param apiKey         klucz; nigdy nie może trafić do logu ani do komunikatu wyjątku
     * @param connectTimeout limit na nawiązanie połączenia
     * @param readTimeout    limit na odpowiedź; BEZ niego zawieszony dostawca trzyma wątek
     *                       z puli tłumaczeń w nieskończoność, a przy concurrency=2
     *                       wystarczą dwa takie żądania, żeby kolejka stanęła bez śladu
     *                       w logach
     */
    public record DeepL(
            String apiUrl,
            String apiKey,
            @NotNull Duration connectTimeout,
            @NotNull Duration readTimeout
    ) {
    }
}
