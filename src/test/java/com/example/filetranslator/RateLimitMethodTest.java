package com.example.filetranslator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Reguła limitu zawężona do jednej metody HTTP.
 *
 * DLACZEGO TA MOŻLIWOŚĆ ISTNIEJE: koszt żądań pod jedną ścieżką potrafi się różnić o rzędy
 * wielkości. POST /translations woła płatne API dostawcy, GET tej samej ścieżki czyta wiersz
 * z bazy. Jedna wspólna reguła zmuszałaby do wyboru między progiem tak wysokim, że nie chroni
 * przed niczym, a progiem, o który odbija się zwykłe odpytywanie o status.
 *
 * Reguła BEZ podanej metody obejmuje wszystkie - to jest zachowanie wszystkich istniejących
 * reguł dla /auth/**, więc dołożenie tego pola niczego w nich nie zmieniło.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "app.rate-limit.enabled=true",
        "app.rate-limit.policies[0].path=/translations",
        "app.rate-limit.policies[0].method=POST",
        "app.rate-limit.policies[0].capacity=1",
        "app.rate-limit.policies[0].period=1h"
})
class RateLimitMethodTest {

    @Autowired
    private MockMvc mockMvc;

    /**
     * JEDNA metoda testowa, a nie dwie, i to nie jest lenistwo: kubełki są STANOWE i żyją tak
     * długo jak kontekst Springa, więc żetony zabrane w jednej metodzie nie wracają przed
     * następną. Dwie metody biłyby się o ten sam kubełek i wynik zależałby od kolejności ich
     * wykonania - ta sama pułapka, którą RateLimitTest obchodzi osobnymi ścieżkami.
     *
     * Żądania lecą bez uwierzytelnienia i kończą się na 401 - i to wystarcza, bo limiter stoi
     * w łańcuchu PRZED uwierzytelnianiem. Badamy, czy żeton został zabrany, a nie co
     * odpowiedział kontroler.
     */
    @Test
    @DisplayName("GET nie zużywa żetonów POST-a, a próg POST-a nadal działa")
    void get_shouldNotConsumePostTokens() throws Exception {
        // Pięć GET-ów - tyle, że gdyby zużywały żetony, kubełek o pojemności 1 byłby pusty
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(get("/translations"))
                    .andExpect(status().isUnauthorized());
        }

        // Kubełek POST-a musi być nadal pełny mimo tych pięciu GET-ów. Gdyby metoda nie była
        // częścią reguły, frontend odpytujący o listę wyczerpywałby próg przeznaczony
        // na zlecanie tłumaczeń - użytkownik dostawałby 429 za samo patrzenie na ekran.
        mockMvc.perform(post("/translations").with(csrf()))
                .andExpect(status().isUnauthorized());

        // ...a sam próg dla POST-a dalej obowiązuje
        mockMvc.perform(post("/translations").with(csrf()))
                .andExpect(status().isTooManyRequests());
    }
}
