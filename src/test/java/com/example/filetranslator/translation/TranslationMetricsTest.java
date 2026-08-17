/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.filetranslator.translation;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Liczniki tłumaczenia istnieją OD STARTU, a nie dopiero od pierwszego zdarzenia.
 *
 * Micrometer zakłada licznik przy pierwszym increment(), więc bez jawnej rejestracji
 * /actuator/metrics/translation.chars.translated odpowiada 404, dopóki nikt niczego nie
 * przetłumaczy. Odpowiedź znaczy "zero", a czyta się jak literówka w nazwie albo awaria -
 * i to akurat przy jedynej metryce mówiącej, ile znaków wydano u dostawcy.
 *
 * WŁASNY KONTEKST (unikalne @TestPropertySource) jest tu warunkiem sensu testu: w kontekście
 * współdzielonym z innymi klasami liczniki istniałyby dlatego, że sąsiad zdążył coś
 * przetłumaczyć, i test przechodziłby także bez naprawy. Wyłączony worker gwarantuje przy
 * okazji, że w trakcie testu nic nie wykona się w tle i wartości zostaną zerowe.
 *
 * Ta sama pomyłka co 404 na /actuator/metrics przy wąskim exposure na prodzie (CLAUDE.md):
 * reguła bezpieczeństwa albo licznik "przeżywają" endpoint, więc brak odpowiedzi wygląda
 * na zepsutą aplikację zamiast na brak danych.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "app.translation.enabled=false")
class TranslationMetricsTest {

    @Autowired
    private MeterRegistry meters;

    @Test
    @DisplayName("Liczniki znaków istnieją przed pierwszym tłumaczeniem i pokazują zero")
    void charCounters_shouldExistBeforeFirstJob() {
        assertThat(counter("translation.chars.translated"))
                .as("bez tego /actuator/metrics/translation.chars.translated zwraca 404, "
                        + "co czyta się jak awaria, a znaczy zero wydanych znaków")
                .isNotNull()
                .extracting(Counter::count)
                .isEqualTo(0.0d);

        assertThat(counter("translation.chars.saved"))
                .isNotNull()
                .extracting(Counter::count)
                .isEqualTo(0.0d);
    }

    /**
     * Trafienia BEZ pudeł nie dają współczynnika, a pytanie brzmi "czy deduplikacja się
     * opłaca", nie "czy w ogóle działa" - dlatego oba warianty tagu, nie jeden.
     */
    @Test
    @DisplayName("Licznik cache'a istnieje w obu wariantach wyniku")
    void cacheCounter_shouldExistForHitAndMiss() {
        assertThat(meters.find("translation.cache").tag("result", "hit").counter())
                .as("trafienia").isNotNull();
        assertThat(meters.find("translation.cache").tag("result", "miss").counter())
                .as("pudła - bez nich nie da się policzyć współczynnika").isNotNull();
    }

    private Counter counter(String name) {
        return meters.find(name).counter();
    }
}
