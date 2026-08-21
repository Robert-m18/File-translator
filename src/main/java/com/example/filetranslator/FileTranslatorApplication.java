/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.filetranslator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.ZoneId;
import java.util.TimeZone;

/**
 * Punkt wejścia aplikacji.
 *
 * Skanowanie klas konfiguracyjnych rejestruje wszystkie typy ustawień bez wypisywania każdego
 * z osobna, a włączony harmonogram uruchamia zadania cykliczne: wysyłkę poczty, wykonywanie
 * zleceń tłumaczenia i nocne sprzątanie.
 */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class FileTranslatorApplication {

	/** Strefa prezentacji - obowiązuje logi i wyrażenia harmonogramu, nie dane w bazie. */
	static final ZoneId DISPLAY_ZONE = ZoneId.of("Europe/Warsaw");

	public static void main(String[] args) {
		enforceDisplayTimeZone();
		SpringApplication.run(FileTranslatorApplication.class, args);
	}

	/**
	 * Ustawia strefę domyślną maszyny wirtualnej, zanim cokolwiek odczyta zegar.
	 *
	 * Strefa nie ma wpływu na dane: kolumny czasowe przechowują punkt na osi czasu razem ze
	 * strefą, a encje operują na typie bez wall-clockowej godziny, więc dwie instancje pracujące
	 * w różnych strefach zapiszą tę samą wartość. Z tego samego powodu nie dotyczy jej problem
	 * zmiany czasu, przy której godzina nocna raz się powtarza, a raz nie występuje.
	 *
	 * Ustawienie służy dwóm rzeczom. Po pierwsze, logi wszystkich instancji mają jedną strefę,
	 * więc znaczniki z różnych środowisk da się porównywać wprost. Po drugie, wyrażenia
	 * harmonogramu interpretują godzinę w strefie domyślnej, więc bez tego nocne sprzątanie
	 * uruchamiałoby się o innej porze na maszynie lokalnej niż w kontenerze. Godziny zadań są
	 * dobrane tak, by nie kolidowały ze zmianą czasu.
	 *
	 * Strefa jest nazwana, a nie podana jako stałe przesunięcie, dzięki czemu sama uwzględnia
	 * czas letni i zimowy.
	 *
	 * Ustawienie znajduje się w kodzie, a nie w konfiguracji kontenera czy środowiska
	 * uruchomieniowego, ponieważ ustawienie zewnętrzne trzeba pamiętać w każdym środowisku,
	 * a jego pominięcie nie daje przy starcie żadnego objawu. Wywołanie poprzedza start
	 * kontekstu, bo strefa musi obowiązywać, zanim powstanie pierwszy komponent czytający zegar.
	 *
	 * Przy oglądaniu bazy warto pamiętać, że klient SQL wyświetla czas w swojej własnej strefie,
	 * a nie w tej.
	 */
	static void enforceDisplayTimeZone() {
		TimeZone.setDefault(TimeZone.getTimeZone(DISPLAY_ZONE));
	}

}
