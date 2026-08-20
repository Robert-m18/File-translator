package com.example.filetranslator.auth.oauth2;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * APLIKACJA MA WSTAWAĆ BEZ SKONFIGUROWANEGO KLIENTA GOOGLE.
 *
 * To jest regresja do awarii z 2026-08-20: `docker compose up -d --build` bez
 * GOOGLE_CLIENT_ID w .env NIE PODNOSIŁ KONTENERA. Przyczyną było założenie, że pusty
 * client-id znaczy tyle co brak konfiguracji - nie znaczy. Zadeklarowana rejestracja
 * z pustą wartością to dla Springa rejestracja BŁĘDNA, a nie nieobecna:
 * OAuth2ClientProperties.validate() rzuca wtedy "Client id of registration 'google'
 * must not be empty" i cały kontekst nie powstaje.
 *
 * DLACZEGO NIE ZŁAPAŁA TEGO RESZTA SUITE: application-test.yml podaje atrapowe dane
 * klienta, więc KAŻDY kontekst testowy miał poprawną rejestrację i ścieżka "bez
 * konfiguracji" nie wykonywała się ani razu. Dokładnie ta sama ślepota, co przy teście
 * SameSite dwa dni wcześniej - profil testowy ustawiał już wartość poprawną, więc gałąź
 * defektu była nieosiągalna. Stąd ta klasa ustawia PUSTE wartości u siebie: bez tego
 * nie ma czego sprawdzać.
 *
 * Cena: własny kontekst w cache'u, czyli własna pula połączeń. Zapłacona świadomie -
 * chroni przed awarią, która kładzie całe wdrożenie, a nie psuje jedną funkcję.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "app.oauth2.google.client-id=",
        "app.oauth2.google.client-secret=",
})
class GoogleLoginDisabledTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApplicationContext context;

    /**
     * Sam fakt wstrzyknięcia zależności oznacza, że kontekst powstał - czyli to,
     * czego brakowało. Asercja niżej pilnuje, żeby powstał z WYŁĄCZONYM logowaniem
     * Google, a nie z jakąś atrapą klienta.
     */
    @Test
    @DisplayName("Bez danych klienta kontekst wstaje, a rejestracji Google nie ma")
    void withoutClientCredentials_contextShouldStart() {
        assertThat(context.getBeanNamesForType(ClientRegistrationRepository.class)).isEmpty();
    }

    /**
     * Ścieżka logowania Google ma po prostu NIE ISTNIEĆ, gdy nie ma klienta.
     *
     * 404, a nie 401: ścieżka jest w PUBLIC_ENDPOINTS, więc reguła autoryzacji jej nie
     * broni - po prostu nikt jej nie obsługuje, bo filtr OAuth2 nie został wpięty.
     */
    @Test
    @DisplayName("Bez danych klienta /oauth2/authorization/google nie jest obsługiwane")
    void withoutClientCredentials_authorizationPathShouldNotExist() throws Exception {
        mockMvc.perform(get("/oauth2/authorization/google"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(404));
    }
}
