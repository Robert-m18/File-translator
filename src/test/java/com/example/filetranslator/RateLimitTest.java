package com.example.filetranslator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Test limitu liczby żądań.
 *
 * Limiter jest domyślnie wyłączony na profilu testowym (kubełki są stanowe i przenosiłyby
 * się między testami), więc ta klasa włącza go u siebie i ustawia bardzo niski próg.
 * @TestPropertySource tworzy osobny kontekst Springa, dzięki czemu limit nie wpływa
 * na pozostałe testy.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "app.rate-limit.enabled=true",
        "app.rate-limit.policies[0].path=/auth/login",
        "app.rate-limit.policies[0].capacity=3",
        "app.rate-limit.policies[0].period=1m",
        // Osobna ścieżka i osobny kubełek dla testu nagłówków CORS. Gdyby oba testy biły się
        // o ten sam kubełek, wynik zależałby od kolejności ich wykonania - żetony nie wracają
        // między metodami, bo limiter jest stanowy.
        "app.rate-limit.policies[1].path=/auth/refresh",
        "app.rate-limit.policies[1].capacity=1",
        "app.rate-limit.policies[1].period=1m"
})
class RateLimitTest {

    @Autowired
    private MockMvc mockMvc;

    /** Musi zgadzać się z app.frontend.url na profilu testowym - inaczej CORS odrzuci origin. */
    private static final String FRONTEND_ORIGIN = "http://localhost:5173";

    private static final String BODY = """
            {"email":"ktokolwiek@example.com","password":"JakiesHaslo1"}
            """;

    @Test
    @DisplayName("Po wyczerpaniu limitu endpoint logowania zwraca 429 z nagłówkiem Retry-After")
    void shouldReturn429_afterExceedingLimit() throws Exception {
        // Trzy żądania mieszczą się w limicie. Zwracają 401 (konto nie istnieje),
        // ale ważne jest, że w ogóle docierają do logiki uwierzytelniania.
        for (int i = 0; i < 3; i++) {
            int status = mockMvc.perform(post("/auth/login")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(BODY))
                    .andReturn().getResponse().getStatus();
            assertThat(status).isEqualTo(401);
        }

        // Czwarte żądanie zostaje odcięte PRZED sprawdzeniem hasła - to jest cel limitera:
        // atak siłowy nie może obciążać bazy ani kosztownego porównania hasha BCrypt.
        mockMvc.perform(post("/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isTooManyRequests())
                .andExpect(content -> assertThat(content.getResponse().getContentType())
                        .contains("application/problem+json"))
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.retryAfterSeconds").isNumber());
    }

    /**
     * Regresja na kolejność filtrów.
     *
     * Odpowiedź 429 musi nieść nagłówki CORS, inaczej przeglądarka ją zablokuje i frontend
     * stojący na innym origin dostanie błąd sieci od fetch() zamiast komunikatu "spróbuj za X s" -
     * dla użytkownika nie do odróżnienia od padniętego serwera. Tak było, dopóki RateLimitFilter
     * stał przed całym łańcuchem Spring Security, a więc i przed CorsFilter.
     *
     * Sam status 429 tej pomyłki NIE wykrywa (sprawdza to test wyżej i był zielony przez cały
     * czas trwania błędu) - dlatego asercja idzie po nagłówku, nie po kodzie odpowiedzi.
     */
    @Test
    @DisplayName("Odpowiedź 429 niesie nagłówki CORS, więc przeglądarka jej nie zablokuje")
    void rateLimited429_shouldCarryCorsHeaders() throws Exception {
        // Kubełek /auth/refresh ma pojemność 1 - pierwsze żądanie ją wyczerpuje.
        mockMvc.perform(post("/auth/refresh")
                        .with(csrf())
                        .header(HttpHeaders.ORIGIN, FRONTEND_ORIGIN))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/auth/refresh")
                        .with(csrf())
                        .header(HttpHeaders.ORIGIN, FRONTEND_ORIGIN))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Access-Control-Allow-Origin", FRONTEND_ORIGIN))
                // Bez tego nagłówka przeglądarka nie przepuści odpowiedzi na żądanie
                // wysłane z credentials: 'include', czyli na każde żądanie tego frontendu.
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
    }

    @Test
    @DisplayName("Ścieżki bez skonfigurowanej reguły nie są limitowane")
    void shouldNotLimitUnconfiguredPaths() throws Exception {
        // /auth/register nie ma reguły w tym teście - powtarzalne żądania muszą przechodzić.
        // Sprawdza, że filtr nie limituje przypadkiem wszystkiego jak leci.
        for (int i = 0; i < 6; i++) {
            mockMvc.perform(post("/auth/register")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"name":"X","email":"zly-email","password":"x"}
                                    """))
                    .andExpect(status().isBadRequest());
        }
    }
}
