/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.filetranslator.translation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Ustawienia tłumaczenia plików (prefiks app.translation).
 *
 * Typ jest walidowany, więc błędna wartość przerywa start aplikacji zamiast ujawniać się przy
 * pierwszym zleceniu.
 */
@Validated
@ConfigurationProperties(prefix = "app.translation")
public record TranslationProperties(

        /**
         * Czy wykonawca ma cyklicznie pobierać zlecenia z kolejki. W testach wyłączone: wykonawca
         * działający w tle zabierałby wiersze, które testy chcą sprawdzić, a wynik zależałby od
         * tego, co zdąży się wykonać. Testy wywołują cykl wprost.
         */
        boolean enabled,

        /** Dostawca wykonujący tłumaczenie. */
        @NotNull Provider provider,

        /**
         * Ile znaków użytkownik może zlecić w ciągu doby.
         *
         * Nie jest to ochrona serwera - od tego jest limiter żądań - tylko ochrona konta
         * u dostawcy. Limit darmowego planu liczy się dla całego konta, więc jeden użytkownik
         * działający w pętli wyczerpałby go dla wszystkich pozostałych. Limit na adres IP tego nie
         * zatrzyma, bo zalogowany użytkownik zmienia adres bez utraty tożsamości.
         */
        @Positive int dailyCharLimit,

        /** Ile zleceń pobierać na jeden cykl. */
        @Positive int batchSize,

        /**
         * Ile zleceń z jednej paczki tłumaczyć równolegle.
         *
         * Wartość musi być ograniczona: dostawcy limitują liczbę równoczesnych żądań, a jej
         * przekroczenie kończy się odrzuceniem całej paczki. Pula bez ograniczenia zamieniłaby
         * chwilowy ruch w serię ponowień.
         */
        @Positive int concurrency,

        /** Po tylu podejściach zlecenie zostaje oznaczone jako nieudane. */
        @Positive int maxAttempts,

        /** Podstawa odstępu między próbami; odstęp rośnie wykładniczo z liczbą podejść. */
        @NotNull Duration retryBackoff,

        /**
         * Na jak długo instancja rezerwuje zlecenie, zabierając się do tłumaczenia.
         *
         * Musi być wyraźnie dłuższe niż realny czas odpowiedzi dostawcy, inaczej druga instancja
         * pobierze to samo zlecenie, gdy pierwsza jeszcze czeka na wynik.
         */
        @NotNull Duration claimTimeout,

        /**
         * Co ile pytać dostawcę, czy dokument jest już przetłumaczony.
         *
         * Wartość odrębna od częstotliwości odpytywania kolejki: tamta mówi, jak często zaglądać
         * do własnej bazy, ta - jak często odpytywać zewnętrzne API, co liczy się do limitu żądań
         * dostawcy. Zbyt krótka zamienia jeden dokument w serię żądań, zbyt długa dokłada zwłokę
         * do czasu, który użytkownik i tak spędza przy ekranie.
         */
        @NotNull Duration documentPollInterval,

        /**
         * Jak długo przechowywane są zlecenia, licząc od chwili utworzenia.
         *
         * Chodzi o treść, nie o rozmiar tabeli: zlecenia wskazują pliki użytkowników.
         * Bezterminowe przechowywanie oznaczałoby, że wyciek danych obejmuje wszystko, co
         * ktokolwiek kiedykolwiek tłumaczył. Użytkownik może skasować zlecenie sam, a retencja
         * jest tym, co dzieje się, gdy tego nie zrobi.
         */
        @NotNull Duration retention,

        @NotNull @Valid DeepL deepl
) {

    public enum Provider {
        /** Atrapa - oznacza tekst zamiast tłumaczyć. Używana w testach i lokalnie, bez klucza API. */
        ECHO,
        /** Prawdziwe API DeepL. Wymaga ustawionego klucza. */
        DEEPL
    }

    /**
     * @param apiUrl         podstawa adresu API; konta darmowe mają inny host niż płatne,
     *                       a pomyłka kończy się odrzuceniem uwierzytelnienia
     * @param apiKey         klucz API; nigdy nie trafia do logu ani do komunikatu wyjątku
     * @param connectTimeout limit czasu na nawiązanie połączenia
     * @param readTimeout    limit czasu na odpowiedź; bez niego zawieszony dostawca zajmowałby
     *                       wątek z puli tłumaczeń w nieskończoność, a przy niewielkiej
     *                       równoległości wystarczyłyby dwa takie żądania, żeby kolejka stanęła
     *                       bez żadnego śladu w logach
     */
    public record DeepL(
            String apiUrl,
            String apiKey,
            @NotNull Duration connectTimeout,
            @NotNull Duration readTimeout
    ) {
    }
}
