/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.robert;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.ZoneId;
import java.util.TimeZone;

/**
 * @ConfigurationPropertiesScan - rejestruje klasy @ConfigurationProperties (np. CookieProperties)
 * bez wypisywania każdej z osobna w @EnableConfigurationProperties.
 */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class FileTranslatorApplication {

	/**
	 * Strefa prezentacji: logi i wyrażenia cron. NIE ma wpływu na dane w bazie - patrz niżej.
	 *
	 * Nazwana strefa, nie stały offset (nie ZoneOffset.ofHours(2)): sama pilnuje przejścia
	 * na czas letni, więc "3:00" w cronie zostaje trzecią w nocy przez cały rok.
	 */
	static final ZoneId DISPLAY_ZONE = ZoneId.of("Europe/Warsaw");

	public static void main(String[] args) {
		enforceDisplayTimeZone();
		SpringApplication.run(FileTranslatorApplication.class, args);
	}

	/**
	 * Ustawia strefę domyślną JVM-a, ZANIM cokolwiek odczyta zegar.
	 *
	 * HISTORIA, bo bez niej to ustawienie wygląda na kosmetykę. Kolumny czasowe były typu
	 * "timestamp WITHOUT time zone", a kod zapisywał do nich LocalDateTime.now(), czyli gołą
	 * ścianę zegara w strefie domyślnej JVM-a. Strefa nie szła razem z wartością, więc
	 * instancja na hoście (Europe/Warsaw) i instancja w kontenerze (UTC) wpisywały do jednej
	 * tabeli dwa różne zegary i nic tego nie sygnalizowało: 2026-08-08 w outbox_messages
	 * wiersz o id 4 miał created_at 11:28, a zapisany PRZED nim wiersz o id 3 - godzinę 13:14.
	 * Doraźnie wymuszano tu wtedy UTC, żeby wszystkie instancje pisały jednym zegarem.
	 *
	 * DLACZEGO TERAZ MOŻE TO BYĆ STREFA LOKALNA - changeset 0006 przestawił kolumny na
	 * "WITH time zone", a encje na Instant. Zapisywany jest punkt na osi czasu razem ze strefą,
	 * więc strefa domyślna JVM-a nie ma już wpływu na to, CO trafia do bazy - dwie instancje
	 * o różnych strefach zapiszą tę samą wartość. Zniknął przy okazji drugi powód, dla którego
	 * strefa lokalna była tu wcześniej nie do przyjęcia: zmiana czasu. Przy kolumnie bez strefy
	 * powtórzona godzina 02:00-02:59 w ostatnią niedzielę października cofa zapisywane znaczniki
	 * i psuje warunki typu "next_retry_at &lt;= now"; Instant nie ma godziny, którą dałoby się
	 * powtórzyć albo pominąć, więc problem nie występuje.
	 *
	 * ZOSTAJE, mimo że nie chroni już danych, i to z dwóch powodów:
	 *  - logi wszystkich instancji mają jedną strefę, więc znaczniki z hosta i z kontenera
	 *    da się porównywać wprost, bez pytania "czyj to zegar",
	 *  - @Scheduled(cron) interpretuje godzinę w strefie domyślnej JVM-a, więc bez tego nocne
	 *    sprzątanie chodziłoby o dwie godziny inaczej na hoście niż w kontenerze.
	 * Crony 3:00 i 3:10 są bezpieczne wobec zmiany czasu w Polsce: na wiosnę przeskok idzie
	 * z 02:00 na 03:00 (trzecia istnieje), jesienią z 03:00 na 02:00 (trzecia wypada raz).
	 *
	 * Dlaczego w kodzie, a nie przez TZ w docker-compose czy w konfiguracji IDE: ustawienie
	 * poza aplikacją trzeba pamiętać w KAŻDYM środowisku, a pominięcie go w jednym nie daje
	 * żadnego objawu przy starcie.
	 *
	 * Dlaczego przed SpringApplication.run: strefa musi być ustawiona, zanim powstanie
	 * pierwszy bean czytający zegar.
	 *
	 * UWAGA przy oglądaniu bazy: klient SQL pokazuje timestamptz w SWOJEJ strefie, nie w tej.
	 * Domyślnie jest to strefa systemowa maszyny, czyli zwykle ta sama godzina co tutaj.
	 */
	static void enforceDisplayTimeZone() {
		TimeZone.setDefault(TimeZone.getTimeZone(DISPLAY_ZONE));
	}

}
