/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.filetranslator.auth.oauth2;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Dokąd na froncie odesłać przeglądarkę po logowaniu przez Google (prefiks app.oauth2).
 *
 * Osobno od app.frontend.url, bo tamto jest ORIGIN-em (używanym przez CORS i przy sklejaniu
 * linków w mailach), a to są TRASY w routingu SPA. Sklejenie obu w jedną właściwość
 * oznaczałoby, że zmiana trasy logowania na froncie wymaga ruszania konfiguracji CORS.
 *
 * @Validated, bo pusta trasa dałaby przekierowanie pod sam origin - czyli logowanie
 * kończyłoby się w losowym miejscu aplikacji, bez żadnego błędu po drodze.
 */
@Validated
@ConfigurationProperties(prefix = "app.oauth2")
public record GoogleOAuth2Properties(

        /** Trasa na froncie po UDANYM logowaniu. Sesja stoi już na ciasteczkach. */
        @NotBlank String successPath,

        /**
         * Trasa na froncie po NIEUDANYM logowaniu. Doklejany jest do niej parametr
         * error= z kodem, po którym front się rozgałęzia - tak samo, jak rozgałęzia się
         * po polu code z /auth/me.
         */
        @NotBlank String failurePath,

        /**
         * Dane klienta OAuth2 z Google Cloud Console. Puste = logowanie przez Google wyłączone.
         *
         * CELOWO NIE @NotBlank i celowo NIE pod spring.security.oauth2.client.registration -
         * uzasadnienie przy samym polu w klasie Google poniżej.
         */
        Google google
) {

    /**
     * Domyślnie pusty zestaw danych klienta, gdy bloku app.oauth2.google nie ma w ogóle.
     *
     * Bez tego nieustawiona sekcja daje null i pierwsze odwołanie do properties.google()
     * kończy się NPE przy starcie - czyli znowu awarią zamiast wyłączonej funkcji, tyle
     * że innym wyjątkiem.
     */
    public GoogleOAuth2Properties {
        if (google == null) {
            google = new Google(null, null);
        }
    }

    /**
     * Dane klienta OAuth2.
     *
     * DLACZEGO NIE spring.security.oauth2.client.registration.google - to jest sedno
     * awarii z 2026-08-20 i powód, dla którego ta klasa w ogóle istnieje.
     *
     * Tamta ścieżka wygląda na właściwą i jest nią, dopóki klient ZAWSZE jest skonfigurowany.
     * Przy pustych wartościach Spring Boot NIE traktuje rejestracji jako nieobecnej, tylko
     * jako BŁĘDNĄ: OAuth2ClientProperties.afterPropertiesSet() woła validate(), a ta rzuca
     * "Client id of registration 'google' must not be empty" i cały kontekst nie powstaje.
     * Skutek: `docker compose up -d --build` bez GOOGLE_CLIENT_ID w .env nie podnosił
     * kontenera - czyli dokładnie ta awaria, której miało nie być.
     *
     * Nie da się tego obejść w samym yml-u (nie ma warunkowego bloku), ani przez
     * @ConditionalOnProperty (traktuje pusty łańcuch jako wartość obecną). Dlatego dane
     * przychodzą tutaj, a rejestrację buduje GoogleClientRegistrationConfig pod własnym
     * warunkiem sprawdzającym, że obie wartości są NIEPUSTE.
     */
    public record Google(String clientId, String clientSecret) {

        /** Czy w ogóle jest z czego zbudować rejestrację. Pusty łańcuch to brak wartości. */
        public boolean isConfigured() {
            return clientId != null && !clientId.isBlank()
                    && clientSecret != null && !clientSecret.isBlank();
        }
    }
}
