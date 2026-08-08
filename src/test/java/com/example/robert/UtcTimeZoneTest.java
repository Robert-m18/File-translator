package com.example.robert;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Aplikacja liczy czas w UTC niezależnie od strefy maszyny.
 *
 * DLACZEGO - kolumny czasowe to "timestamp WITHOUT time zone", a kod zapisuje do nich
 * LocalDateTime.now(), czyli czas w strefie domyślnej JVM-a. Strefa nie jedzie razem
 * z wartością, więc instancja na hoście (Europe/Warsaw) i instancja w kontenerze (UTC)
 * wpisywały do jednej tabeli czasy różniące się o dwie godziny. Zaobserwowane 2026-08-08
 * w outbox_messages: wiersz o id 4 miał created_at 11:28, a zapisany PRZED nim wiersz
 * o id 3 - godzinę 13:14.
 *
 * Ten test sprawdza sam mechanizm: że po wymuszeniu strefy LocalDateTime.now() pokrywa się
 * z zegarem UTC. NIE sprawdza, że main() to wywołuje - tego z testu nie widać, bo testy nie
 * przechodzą przez main(). Dlatego wywołanie w main() jest opisane komentarzem wyjaśniającym,
 * czego dotyczy, a nie samym "ustawiamy strefę".
 *
 * Strefa jest przywracana w @AfterEach: to stan globalny JVM-a, a testy dzielą jedną maszynę
 * wirtualną - pozostawiona zmiana przeciekłaby na kolejne klasy testowe.
 */
class UtcTimeZoneTest {

    private final TimeZone original = TimeZone.getDefault();

    @AfterEach
    void restoreTimeZone() {
        TimeZone.setDefault(original);
    }

    @Test
    @DisplayName("Po wymuszeniu strefy zegar aplikacji jest zegarem UTC")
    void enforceUtcTimeZone_shouldMakeLocalDateTimeMatchUtc() {
        // Punkt wyjścia: cokolwiek, byle nie UTC - inaczej test przechodziłby na maszynie
        // w UTC nawet z usuniętym wymuszeniem, czyli nie pilnowałby niczego.
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Warsaw"));
        assertThat(ZoneId.systemDefault()).isNotEqualTo(ZoneOffset.UTC);

        FileTranslatorApplication.enforceUtcTimeZone();

        assertThat(ZoneId.systemDefault().getRules().getOffset(Instant.now()))
                .isEqualTo(ZoneOffset.UTC);
        // Sedno: to, co trafia do kolumny bez strefy, jest czasem UTC. Margines na samo
        // wykonanie dwóch instrukcji, nie na różnicę stref - ta wynosiłaby godziny.
        assertThat(Duration.between(LocalDateTime.now(ZoneOffset.UTC), LocalDateTime.now()).abs())
                .isLessThan(Duration.ofSeconds(1));
    }
}
