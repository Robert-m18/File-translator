/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.robert;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.ZoneOffset;
import java.util.TimeZone;

/**
 * @ConfigurationPropertiesScan - rejestruje klasy @ConfigurationProperties (np. CookieProperties)
 * bez wypisywania każdej z osobna w @EnableConfigurationProperties.
 */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class FileTranslatorApplication {

	public static void main(String[] args) {
		enforceUtcTimeZone();
		SpringApplication.run(FileTranslatorApplication.class, args);
	}

	/**
	 * Wymusza UTC jako strefę domyślną JVM-a, ZANIM cokolwiek odczyta zegar.
	 *
	 * DLACZEGO TO ISTNIEJE - kolumny czasowe są typu "timestamp WITHOUT time zone", a kod
	 * zapisuje do nich LocalDateTime.now(), czyli czas w strefie domyślnej JVM-a. Strefa
	 * nie jest nigdzie zapisana razem z wartością, więc dwie instancje o różnych strefach
	 * wpisują do jednej tabeli czasy z DWÓCH ZEGARÓW i nic tego nie sygnalizuje.
	 *
	 * Nie jest to hipoteza - tak wyglądała ta baza 2026-08-08. Wiersz outboxu o id 4 miał
	 * created_at 11:28, a zapisany przed nim wiersz o id 3 - godzinę 13:14. Wiersz 3 zapisała
	 * aplikacja z hosta (Europe/Warsaw, UTC+2), wiersz 4 aplikacja z kontenera (UTC).
	 *
	 * Skutek jest cichy i dotyczy każdego miejsca, w którym zapisany znacznik porównujemy
	 * potem z now():
	 *   - publisher szuka wierszy warunkiem next_retry_at <= now, więc mail zamówiony przez
	 *     instancję "przesuniętą w przód" jest dla instancji o zegarze UTC niewidoczny przez
	 *     całą różnicę stref - dwie godziny leżenia w kolejce, bez żadnego błędu w logach,
	 *   - okno rezerwacji (claim-timeout 2 min) traci sens między takimi instancjami, więc
	 *     ten sam mail może polecieć dwa razy,
	 *   - expires_at tokenów wygasa o różnicę stref za wcześnie albo za późno.
	 *
	 * Dlaczego w kodzie, a nie przez TZ w docker-compose czy w konfiguracji IDE: ustawienie
	 * poza aplikacją trzeba pamiętać w KAŻDYM środowisku, a pominięcie go w jednym nie daje
	 * żadnego objawu przy starcie. Tutaj obowiązuje wszędzie i nie da się go zapomnieć.
	 *
	 * Dlaczego przed SpringApplication.run: strefa domyślna musi być ustawiona, zanim
	 * powstanie pierwszy bean czytający zegar (np. AdminBootstrap zapisujący created_at).
	 *
	 * Świadomy skutek uboczny: logi na hoście mają teraz czas UTC, czyli o różnicę stref
	 * inny niż zegar systemowy. To jest cena tego, że czas w logach, w bazie i w kontenerze
	 * jest wreszcie jedną i tą samą wartością. Cron nocnego sprzątania (3:00) to również UTC.
	 *
	 * Właściwym rozwiązaniem docelowym jest timestamptz + Instant, wtedy strefa jedzie razem
	 * z wartością i to ustawienie przestaje być potrzebne. To migracja wszystkich kolumn
	 * czasowych i zmiana encji, więc osobne zadanie - a do tego czasu jedna strefa wszędzie.
	 */
	static void enforceUtcTimeZone() {
		TimeZone.setDefault(TimeZone.getTimeZone(ZoneOffset.UTC));
	}

}
