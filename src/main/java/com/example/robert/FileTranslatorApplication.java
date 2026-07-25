/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.robert;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * @ConfigurationPropertiesScan - rejestruje klasy @ConfigurationProperties (np. CookieProperties)
 * bez wypisywania każdej z osobna w @EnableConfigurationProperties.
 */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class FileTranslatorApplication {

	public static void main(String[] args) {
		SpringApplication.run(FileTranslatorApplication.class, args);
	}

}
