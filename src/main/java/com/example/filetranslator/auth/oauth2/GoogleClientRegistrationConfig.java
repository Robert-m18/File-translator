/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.filetranslator.auth.oauth2;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;

/**
 * Buduje rejestrację klienta Google - ale TYLKO wtedy, gdy dane klienta faktycznie są.
 *
 * DLACZEGO TO NIE JEST ZWYKŁY BLOK W application.yml. Standardowy zapis
 * spring.security.oauth2.client.registration.google.* działa bez zarzutu, dopóki klient
 * jest ZAWSZE skonfigurowany. My chcemy, żeby brak konfiguracji był normalnym stanem -
 * ta sama zasada, dla której domyślnym dostawcą tłumaczenia jest atrapa "echo": bez
 * konfiguracji ma działać aplikacja z mniejszą liczbą funkcji, a nie żadna.
 *
 * Tymczasem pusta wartość NIE ZNACZY tam "brak rejestracji", tylko "rejestracja błędna":
 * OAuth2ClientProperties.validate() rzuca "Client id of registration 'google' must not be
 * empty" i kontekst nie powstaje. Kosztowało to awarię 2026-08-20 - kontener na
 * `docker compose up -d --build` bez GOOGLE_CLIENT_ID w .env nie wstawał w ogóle.
 *
 * Trzy obejścia, które NIE działają, żeby nikt nie próbował ich po raz drugi:
 *  - yml nie ma bloków warunkowych, więc rejestracji nie da się zadeklarować "czasem";
 *  - @ConditionalOnProperty uznaje PUSTY ŁAŃCUCH za wartość obecną, więc dopasowałby się
 *    dokładnie w tym przypadku, który ma odsiać;
 *  - zwrócenie null z metody @Bean daje NullBean, czyli obiekt, który udaje brak beana
 *    tylko przy części sposobów wyszukiwania - a Spring Security szuka repozytorium
 *    przez beanOfTypeIncludingAncestors.
 *
 * Stąd jawny Condition poniżej. Skoro w spring.security.oauth2.* nie ma już żadnej
 * rejestracji, autokonfiguracja Boota wycofuje się w całości (jej warunek wymaga co
 * najmniej jednej), a repozytorium dostarcza ten bean.
 */
@Slf4j
@Configuration
@Conditional(GoogleClientRegistrationConfig.GoogleClientConfigured.class)
public class GoogleClientRegistrationConfig {

    /**
     * Sprawdza OBIE wartości i wymaga, żeby były NIEPUSTE.
     *
     * Własny warunek zamiast @ConditionalOnProperty właśnie dlatego, że tamten nie odróżnia
     * pustego łańcucha od braku wartości - a to jest jedyne rozróżnienie, na którym tu zależy.
     * Obie wartości, nie sam identyfikator: klient z identyfikatorem i bez sekretu przeszedłby
     * start i wywalił się dopiero przy wymianie kodu autoryzacyjnego, czyli po zalogowaniu
     * się użytkownika u Google - najgorszy możliwy moment na odkrycie literówki w .env.
     */
    static class GoogleClientConfigured implements Condition {

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            String clientId = context.getEnvironment().getProperty("app.oauth2.google.client-id", "");
            String clientSecret = context.getEnvironment().getProperty("app.oauth2.google.client-secret", "");
            return !clientId.isBlank() && !clientSecret.isBlank();
        }
    }

    /**
     * Rejestracja zbudowana z wbudowanego szablonu dla Google.
     *
     * CommonOAuth2Provider.GOOGLE zna adresy końcówek, klucz nazwy użytkownika i wzorzec
     * adresu powrotnego ({baseUrl}/login/oauth2/code/{registrationId}) - przepisywanie ich
     * z ręki oznaczałoby, że przy zmianie po stronie Google trzeba je poprawić u siebie.
     *
     * Zakresy podane JAWNIE, mimo że szablon ustawia takie same: openid daje "sub" (nasza
     * tożsamość konta), email daje adres RAZEM z email_verified - bez którego nie wpuszczamy
     * nikogo - a profile nazwę do wyświetlenia. Wpisane wprost, bo od nich zależy działanie
     * GoogleOidcUserService: gdyby szablon kiedyś przestał prosić o email, kontrola
     * potwierdzenia adresu dostawałaby null i odmawiała wszystkim, bez śladu przyczyny.
     *
     * Identyfikator rejestracji "google" jest CZĘŚCIĄ ADRESÓW: /oauth2/authorization/google
     * i /login/oauth2/code/google. Zmiana tutaj wymaga zmiany adresu powrotnego w Google
     * Cloud Console i odnośnika we froncie.
     */
    @Bean
    public ClientRegistrationRepository clientRegistrationRepository(GoogleOAuth2Properties properties) {
        ClientRegistration google = CommonOAuth2Provider.GOOGLE
                .getBuilder("google")
                .clientId(properties.google().clientId())
                .clientSecret(properties.google().clientSecret())
                .scope("openid", "email", "profile")
                .build();

        log.info("Logowanie przez Google jest WŁĄCZONE");
        return new InMemoryClientRegistrationRepository(google);
    }
}
