package com.example.filetranslator.auth.oauth2;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Wejście w przepływ logowania Google: ścieżka publiczna i ciasteczko żądania autoryzacyjnego.
 *
 * Obie sprawdzane tu rzeczy są takie, że ich zepsucie NIE DAJE zrozumiałego objawu -
 * użytkownik widzi "logowanie przez Google nie działa", a komunikaty prowadzą w złą stronę
 * (do konfiguracji klienta OAuth2 albo do Google). Uzasadnienia przy poszczególnych testach.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
/*
 * same-site=Strict WYMUSZONY UMYŚLNIE i to jest warunek, żeby test poniżej cokolwiek
 * znaczył. Profil testowy ma domyślnie Lax - czyli dokładnie tę wartość, którą ciasteczko
 * żądania autoryzacyjnego ma mieć na sztywno. Obie gałęzie (wartość przypięta i wartość
 * z CookieProperties) dawały więc TEN SAM wynik, a test przechodził także z cofniętą
 * poprawką: sprawdzony wprost, nie założony. Strict to zarazem wartość z profilu BAZOWEGO
 * (${COOKIE_SAME_SITE:Strict}), więc ten kontekst odwzorowuje realny układ, w którym
 * pułapka gryzie.
 *
 * Cena: własny wpis w cache'u kontekstów, czyli własna pula połączeń. Świadomie zapłacona -
 * regresja, która nie potrafi zaczerwienieć, jest gorsza niż jej brak, bo wygląda na ochronę.
 */
@TestPropertySource(properties = "app.cookie.same-site=Strict")
class GoogleAuthorizationRequestTest {

    @Autowired
    private MockMvc mockMvc;

    /**
     * Bez /oauth2/** i /login/oauth2/** w PUBLIC_ENDPOINTS obie ścieżki wpadają na
     * anyRequest().authenticated() i kończą się 401 - czyli żeby się zalogować, trzeba
     * być zalogowanym.
     */
    @Test
    @DisplayName("Anonimowe wejście w logowanie Google przekierowuje do dostawcy, a nie 401")
    void anonymousStart_shouldRedirectToGoogle() throws Exception {
        MvcResult result = mockMvc.perform(get("/oauth2/authorization/google"))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        assertThat(result.getResponse().getRedirectedUrl())
                .startsWith("https://accounts.google.com/o/oauth2/v2/auth");
    }

    /**
     * SameSite ciasteczka z żądaniem autoryzacyjnym MUSI być "Lax" i NIE WOLNO go brać
     * z CookieProperties, gdzie profil bazowy ma "Strict".
     *
     * Powrót z accounts.google.com to nawigacja MIĘDZYWITRYNOWA: przy Strict przeglądarka
     * tego ciasteczka nie odeśle, Spring Security nie odnajdzie żądania i całe logowanie
     * kończy się błędem authorization_request_not_found - komunikatem wskazującym na Google
     * i na konfigurację klienta, czyli dokładnie nie tam, gdzie leży przyczyna.
     *
     * Test jest tym ostrzejszy, że na produkcji CookieProperties dają "None", które
     * zadziała: defekt byłby więc widoczny na devie i NIEWIDOCZNY na prodzie. Sprawdzone
     * przez podmianę na cookieProperties.sameSite() - test wtedy czerwienieje.
     */
    @Test
    @DisplayName("Ciasteczko żądania autoryzacyjnego ma SameSite=Lax, nie Strict")
    void authorizationRequestCookie_shouldBeSameSiteLax() throws Exception {
        MvcResult result = mockMvc.perform(get("/oauth2/authorization/google")).andReturn();

        assertThat(result.getResponse().getHeaders("Set-Cookie"))
                .filteredOn(header -> header.startsWith("oauth2_auth_request="))
                .singleElement()
                .satisfies(header -> {
                    assertThat(header).contains("SameSite=Lax");
                    assertThat(header).doesNotContain("SameSite=Strict");
                    // httpOnly, bo frontend nie ma po co tego czytać - a mniej rzeczy
                    // dostępnych dla JavaScriptu to mniej rzeczy do wykradzenia przez XSS.
                    assertThat(header).contains("HttpOnly");
                });
    }

    /**
     * KONTROLA NEGATYWNA dla wpięcia oauth2Login.
     *
     * Konfigurator logowania OAuth2 potrafi PODMIENIĆ domyślny punkt wejścia
     * uwierzytelnienia na przekierowanie do dostawcy. Gdyby wygrał z jawnie ustawionym
     * RestAuthenticationEntryPoint, chronione ścieżki oddawałyby 302 do Google zamiast
     * 401 z ciałem ProblemDetail - a frontend wołający fetch dostałby przekierowanie
     * na cudzą domenę, czyli błąd CORS zamiast czytelnego "sesja wygasła".
     */
    @Test
    @DisplayName("Chroniona ścieżka nadal daje 401 ProblemDetail, a nie przekierowanie do Google")
    void protectedEndpoint_shouldStillReturnProblemDetail() throws Exception {
        mockMvc.perform(get("/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").exists());
    }
}
