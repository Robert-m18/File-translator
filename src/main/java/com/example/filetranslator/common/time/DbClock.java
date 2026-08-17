/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.filetranslator.common.time;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Źródło czasu dla znaczników zapisywanych do bazy - obcięte do precyzji kolumny.
 *
 * DLACZEGO TO ISTNIEJE - to nie jest ozdobnik, tylko naprawa wyścigu, który przez miesiąc
 * wywracał OutboxTest mniej więcej co czwarty przebieg.
 *
 * Instant.now() daje precyzję NANOSEKUNDOWĄ, a kolumny czasowe to TIMESTAMP(6) WITH TIME ZONE,
 * czyli MIKROSEKUNDY. Przy zapisie baza zaokrągla nadmiarowe cyfry - i robi to
 * w GÓRĘ. Znacznik .8891996 ląduje w bazie jako .889200, czyli o 400 ns PÓŹNIEJ, niż
 * wskazywał zegar w chwili zapisu. Wiersz zapisany "na teraz" trafia więc do bazy
 * z czasem w PRZYSZŁOŚCI - do pół mikrosekundy do przodu.
 *
 * Publisher skrzynki nadawczej szuka wierszy warunkiem "next_retry_at <= :now". Jeśli zapis
 * i najbliższy odczyt wypadną w tym samym tyknięciu zegara, zaokrąglony w górę wiersz nie
 * spełnia tego warunku i jest NIEWIDOCZNY dla własnego cyklu - znika dokładnie ten zapisany
 * jako ostatni. W produkcji nieszkodliwe (kolejny cykl za 10 s go weźmie), w testach
 * wołających publishBatch() natychmiast po zapisie - awaria co kilka przebiegów.
 *
 * Obcięcie do mikrosekund sprawia, że wartość zapisana jest DOKŁADNIE tą, którą wygenerował
 * kod, więc żaden późniejszy odczyt zegara nie może okazać się od niej wcześniejszy.
 * Obcinamy, a nie zaokrąglamy: znacznik cofnięty o ułamek mikrosekundy jest bezpieczny,
 * przesunięty w przód - nie.
 *
 * Znalezione 2026-08-04 przez porównanie parametru zapytania (.889199600) z wartością
 * w bazie (.889200) dla wiersza, którego zapytanie nie znalazło. Regresja:
 * OutboxTimestampTest.
 *
 * KIEDY UŻYWAĆ: wszędzie tam, gdzie znacznik zapisany do bazy jest zaraz potem porównywany
 * z "teraz" - czyli w każdej kolejce rezerwującej wiersze przez przesunięcie znacznika
 * w przyszłość (skrzynka nadawcza maili, kolejka zadań tłumaczenia). Znaczniki wygaśnięcia
 * (expires_at, locked_until) są na to odporne: przesunięcie o pół mikrosekundy w przód
 * wydłuża tam ważność o pół mikrosekundy i tyle.
 *
 * DLACZEGO W common/time, A NIE OSOBNO W KAŻDYM PAKIECIE: skopiowana kopia tej samej reguły
 * rozjeżdża się po cichu - projekt przerabiał to już przy TokenHasher, gdzie AuthService
 * miał prywatny duplikat algorytmu hashowania obok klasy współdzielonej.
 *
 * Przejście na timestamptz (changeset 0006) NIC tu nie zmienia i klasa zostaje: strefa
 * i precyzja to dwie różne rzeczy, a timestamptz w PostgreSQL ma tę samą precyzję
 * mikrosekundową co timestamp. Zmienił się tylko typ po stronie Javy - LocalDateTime
 * na Instant - a obie te klasy trzymają nanosekundy.
 */
public final class DbClock {

    /** Precyzja kolumn czasowych: patrz type.timestamptz w db.changelog-master.xml. */
    public static final ChronoUnit COLUMN_PRECISION = ChronoUnit.MICROS;

    private DbClock() {
    }

    public static Instant now() {
        return Instant.now().truncatedTo(COLUMN_PRECISION);
    }

    /** Do znaczników wyliczanych od teraz (backoff, okno rezerwacji) - ta sama zasada. */
    public static Instant truncate(Instant value) {
        return value.truncatedTo(COLUMN_PRECISION);
    }
}
