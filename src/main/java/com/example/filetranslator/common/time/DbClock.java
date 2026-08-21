/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.filetranslator.common.time;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Źródło czasu dla znaczników zapisywanych do bazy, obcięte do precyzji kolumny.
 *
 * Zegar systemowy daje precyzję nanosekundową, a kolumny czasowe przechowują mikrosekundy.
 * Przy zapisie baza zaokrągla nadmiarowe cyfry w górę, więc wiersz zapisany "na teraz" trafia do
 * bazy ze znacznikiem przesuniętym o ułamek mikrosekundy w przyszłość.
 *
 * Ma to praktyczne znaczenie wszędzie tam, gdzie zapisany znacznik jest zaraz potem porównywany
 * z bieżącym czasem. Kolejki wybierają wiersze warunkiem "termin nie później niż teraz", więc
 * wiersz zaokrąglony w górę bywa niewidoczny dla cyklu, który zapisał go przed chwilą - i jest to
 * zawsze ten zapisany jako ostatni. W działającej aplikacji jest to nieszkodliwe, bo weźmie go
 * kolejny cykl, ale w teście wołającym cykl natychmiast po zapisie daje test niestabilny.
 *
 * Obcięcie sprawia, że wartość zapisana jest dokładnie tą, którą wygenerował kod, więc żaden
 * późniejszy odczyt zegara nie może okazać się od niej wcześniejszy. Obcięcie, a nie
 * zaokrąglenie: znacznik cofnięty o ułamek mikrosekundy jest bezpieczny, przesunięty w przód nie.
 *
 * Kiedy stosować: wszędzie tam, gdzie znacznik zapisany do bazy jest zaraz potem porównywany
 * z bieżącym czasem, czyli w każdej kolejce rezerwującej wiersze przez przesunięcie terminu
 * w przyszłość. Znaczniki wygaśnięcia są na ten problem odporne, bo przesunięcie w przód
 * wydłuża tam ważność o tę samą znikomą wartość.
 *
 * Klasa jest wspólna dla całej aplikacji, ponieważ skopiowana reguła rozjeżdża się po cichu -
 * z tego samego powodu wspólny jest algorytm liczenia skrótów.
 *
 * Zapis czasu jako punktu na osi (typ ze strefą) niczego tu nie zmienia: strefa i precyzja to
 * dwie różne sprawy, a używany typ ma tę samą precyzję mikrosekundową.
 */
public final class DbClock {

    /** Precyzja kolumn czasowych, zgodna z definicją typu w changelogu bazy. */
    public static final ChronoUnit COLUMN_PRECISION = ChronoUnit.MICROS;

    private DbClock() {
    }

    public static Instant now() {
        return Instant.now().truncatedTo(COLUMN_PRECISION);
    }

    /** Dla znaczników wyliczanych od teraz - backoffu i okna rezerwacji - obowiązuje ta sama zasada. */
    public static Instant truncate(Instant value) {
        return value.truncatedTo(COLUMN_PRECISION);
    }
}
