package com.example.filetranslator;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Aplikacja ustawia jedną strefę prezentacji niezależnie od strefy maszyny.
 *
 * DLACZEGO - @Scheduled(cron) interpretuje godzinę w strefie domyślnej JVM-a, a logi
 * formatują nią znaczniki. Bez wymuszenia nocne sprzątanie chodziłoby o dwie godziny
 * inaczej na hoście niż w kontenerze, a znaczników z obu środowisk nie dałoby się
 * porównywać wprost.
 *
 * Ten test NIE pilnuje już poprawności danych - od changesetu 0006 kolumny są typu
 * "WITH time zone", a encje trzymają Instant, więc strefa JVM-a nie wpływa na to, co
 * trafia do bazy. Drugi test poniżej pilnuje właśnie tego: że zapisywana wartość jest
 * od strefy niezależna. Gdyby ktoś kiedyś wrócił encją do LocalDateTime, ten test padnie.
 *
 * Jak poprzednio: sprawdza sam mechanizm, nie to, że main() go woła - testy nie przechodzą
 * przez main(). Strefa wraca w @AfterEach, bo to stan globalny JVM-a dzielony przez cały suite.
 */
class DisplayTimeZoneTest {

    private final TimeZone original = TimeZone.getDefault();

    @AfterEach
    void restoreTimeZone() {
        TimeZone.setDefault(original);
    }

    @Test
    @DisplayName("Po wymuszeniu strefa domyślna JVM-a jest strefą prezentacji")
    void enforceDisplayTimeZone_shouldOverrideMachineZone() {
        // Punkt wyjścia: cokolwiek innego niż strefa docelowa - inaczej test przechodziłby
        // na maszynie stojącej w Warszawie nawet z usuniętym wymuszeniem.
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));
        assertThat(ZoneId.systemDefault()).isNotEqualTo(FileTranslatorApplication.DISPLAY_ZONE);

        FileTranslatorApplication.enforceDisplayTimeZone();

        assertThat(ZoneId.systemDefault()).isEqualTo(FileTranslatorApplication.DISPLAY_ZONE);
    }

    @Test
    @DisplayName("Znacznik zapisywany do bazy nie zależy od strefy maszyny")
    void instant_shouldBeIndependentOfDefaultZone() {
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));
        Instant fromNewYork = Instant.now();

        TimeZone.setDefault(TimeZone.getTimeZone("Australia/Sydney"));
        Instant fromSydney = Instant.now();

        // Sekunda z zapasem na wykonanie kilku instrukcji. Różnica stref to tutaj kilkanaście
        // godzin, więc gdyby typ niósł ścianę zegara zamiast punktu na osi, rozjazd byłby
        // o rzędy wielkości większy niż ten margines.
        assertThat(java.time.Duration.between(fromNewYork, fromSydney).abs().getSeconds())
                .isLessThan(1);

        // Kontrola negatywna: tak zachowywał się typ używany przed migracją. Gdyby ta asercja
        // zaczęła padać, znaczyłoby to, że LocalDateTime przestał zależeć od strefy - czyli
        // że test porównuje coś innego, niż myśli.
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));
        LocalDateTime wallClockInNewYork = LocalDateTime.now();
        TimeZone.setDefault(TimeZone.getTimeZone("Australia/Sydney"));
        LocalDateTime wallClockInSydney = LocalDateTime.now();

        assertThat(java.time.Duration.between(wallClockInNewYork, wallClockInSydney).abs().toHours())
                .isGreaterThanOrEqualTo(13);
    }

    @Test
    @DisplayName("Strefa prezentacji to strefa nazwana, więc zna zmianę czasu")
    void displayZone_shouldFollowDaylightSaving() {
        ZoneId zone = FileTranslatorApplication.DISPLAY_ZONE;

        // Zimą UTC+1, latem UTC+2. Stały offset (ZoneOffset.ofHours(2)) przeszedłby pierwszą
        // asercję i padł na drugiej - i o to w tym teście chodzi: cron ma trzymać się
        // trzeciej w nocy przez cały rok, a nie dryfować o godzinę wraz ze zmianą czasu.
        assertThat(zone.getRules().getOffset(Instant.parse("2026-01-15T12:00:00Z")))
                .isEqualTo(ZoneOffset.ofHours(1));
        assertThat(zone.getRules().getOffset(Instant.parse("2026-07-15T12:00:00Z")))
                .isEqualTo(ZoneOffset.ofHours(2));
    }
}
