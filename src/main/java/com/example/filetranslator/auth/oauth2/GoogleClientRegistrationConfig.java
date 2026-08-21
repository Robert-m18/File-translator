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
 * Buduje rejestrację klienta Google wyłącznie wtedy, gdy dane klienta faktycznie istnieją.
 *
 * Standardowy zapis rejestracji w konfiguracji yml działa bez zarzutu, dopóki klient jest
 * zawsze skonfigurowany. Tutaj brak konfiguracji ma być normalnym stanem - ta sama zasada,
 * dla której domyślnym dostawcą tłumaczenia jest atrapa: bez konfiguracji ma działać
 * aplikacja z mniejszą liczbą funkcji, a nie żadna.
 *
 * Pusta wartość nie oznacza tam braku rejestracji, tylko rejestrację błędną: walidacja
 * ustawień klienta przerywa wtedy start kontekstu komunikatem o pustym identyfikatorze,
 * czyli aplikacja nie wstaje w ogóle.
 *
 * Trzy obejścia, które nie działają, zapisane po to, żeby nie próbować ich ponownie:
 *  - yml nie ma bloków warunkowych, więc rejestracji nie da się zadeklarować "czasem";
 *  - warunek na obecność właściwości uznaje pusty łańcuch za wartość obecną, więc pasowałby
 *    dokładnie w przypadku, który ma odsiać;
 *  - zwrócenie null z metody @Bean daje NullBean, czyli obiekt, który udaje brak beana
 *    tylko przy części sposobów wyszukiwania - a Spring Security szuka repozytorium
 *    przez beanOfTypeIncludingAncestors.
 *
 * Stąd jawny warunek poniżej. Skoro w przestrzeni ustawień Spring Security nie ma już żadnej
 * rejestracji, autokonfiguracja frameworka wycofuje się w całości, a repozytorium dostarcza
 * ten komponent.
 */
@Slf4j
@Configuration
@Conditional(GoogleClientRegistrationConfig.GoogleClientConfigured.class)
public class GoogleClientRegistrationConfig {

    /**
     * Sprawdza obie wartości i wymaga, żeby były niepuste.
     *
     * Własny warunek zamiast standardowego właśnie dlatego, że tamten nie odróżnia pustego
     * łańcucha od braku wartości, a to jest jedyne rozróżnienie, na którym tu zależy.
     * Sprawdzane są obie, a nie sam identyfikator: klient bez sekretu przeszedłby start
     * i zawiódłby dopiero przy wymianie kodu autoryzacyjnego, czyli po zalogowaniu się
     * użytkownika u dostawcy - w najgorszym możliwym momencie na odkrycie literówki.
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
     * Wbudowany szablon zna adresy końcówek, klucz nazwy użytkownika i wzorzec
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
