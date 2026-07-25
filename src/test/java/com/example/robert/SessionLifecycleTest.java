package com.example.robert;

import com.example.robert.user.model.Role;
import com.example.robert.user.model.User;
import com.example.robert.user.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pełny cykl życia sesji przez HTTP: logowanie, rotacja tokenu, wykrycie ponownego
 * użycia i wylogowanie.
 *
 * Testy jednostkowe RefreshTokenService sprawdzają samą logikę. Ten test sprawdza,
 * że całość jest poprawnie spięta: ciasteczka są ustawiane z właściwymi atrybutami,
 * kontroler czyta je z właściwej ścieżki, a stan w bazie faktycznie odcina token.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SessionLifecycleTest {

    private static final String EMAIL = "sesja@example.com";
    private static final String PASSWORD = "PoprawneHaslo1";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() {
        userRepository.findByEmail(EMAIL).ifPresent(userRepository::delete);

        User user = new User();
        user.setName("Test Sesji");
        user.setEmail(EMAIL);
        user.setPassword(passwordEncoder.encode(PASSWORD));
        user.setRole(Role.USER);
        user.setEnabled(true);
        userRepository.save(user);
    }

    private MvcResult login() throws Exception {
        String body = """
                {"email":"%s","password":"%s"}
                """.formatted(EMAIL, PASSWORD);

        return mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
    }

    private Cookie cookie(MvcResult result, String name) {
        return Arrays.stream(result.getResponse().getCookies())
                .filter(c -> name.equals(c.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Brak ciasteczka: " + name));
    }

    @Test
    @DisplayName("Logowanie ustawia ciasteczka httpOnly o właściwym zasięgu")
    void login_shouldSetHttpOnlyCookies() throws Exception {
        MvcResult result = login();

        Cookie access = cookie(result, "accessToken");
        Cookie refresh = cookie(result, "refreshToken");

        // httpOnly odcina odczyt tokenu przez JavaScript - podstawowa ochrona przed XSS
        assertThat(access.isHttpOnly()).isTrue();
        assertThat(refresh.isHttpOnly()).isTrue();

        assertThat(access.getPath()).isEqualTo("/");
        // Token odświeżający ograniczony do /auth: nie krąży przy każdym żądaniu do API,
        // ale dociera do /auth/logout, który musi wiedzieć, którą sesję unieważnić
        assertThat(refresh.getPath()).isEqualTo("/auth");
        assertThat(refresh.getMaxAge()).isGreaterThan(access.getMaxAge());
    }

    @Test
    @DisplayName("Odświeżenie wydaje nową parę tokenów i unieważnia poprzedni")
    void refresh_shouldRotateTokens() throws Exception {
        Cookie originalRefresh = cookie(login(), "refreshToken");

        MvcResult refreshed = mockMvc.perform(post("/auth/refresh").cookie(originalRefresh))
                .andExpect(status().isOk())
                .andReturn();

        Cookie newRefresh = cookie(refreshed, "refreshToken");
        assertThat(newRefresh.getValue()).isNotEqualTo(originalRefresh.getValue());
    }

    @Test
    @DisplayName("Ponowne użycie zużytego tokenu unieważnia całą sesję")
    void reusedRefreshToken_shouldKillWholeSession() throws Exception {
        Cookie stolenToken = cookie(login(), "refreshToken");

        // Prawowity użytkownik odświeża sesję - jego token zostaje zużyty
        MvcResult refreshed = mockMvc.perform(post("/auth/refresh").cookie(stolenToken))
                .andExpect(status().isOk())
                .andReturn();
        Cookie currentToken = cookie(refreshed, "refreshToken");

        // Napastnik próbuje użyć wcześniej przechwyconej kopii
        mockMvc.perform(post("/auth/refresh").cookie(stolenToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_REUSED"));

        // Skutek uboczny jest zamierzony: pada cała rodzina, więc token, który
        // przed chwilą był ważny, też przestaje działać. Nie da się rozstrzygnąć,
        // która ze stron jest prawowitym użytkownikiem, więc obie muszą zalogować się ponownie.
        mockMvc.perform(post("/auth/refresh").cookie(currentToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Odświeżenie bez ciasteczka zwraca ProblemDetail, a nie pustą odpowiedź")
    void refreshWithoutCookie_shouldReturnProblemDetail() throws Exception {
        // Puste ciało 401 wywracało front na response.json(). Każdy błąd API,
        // bez wyjątku, musi mieć ten sam kształt.
        mockMvc.perform(post("/auth/refresh"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_MISSING"))
                .andExpect(jsonPath("$.detail").isNotEmpty());
    }

    @Test
    @DisplayName("Wylogowanie unieważnia token odświeżający po stronie serwera")
    void logout_shouldInvalidateRefreshToken() throws Exception {
        Cookie refresh = cookie(login(), "refreshToken");

        mockMvc.perform(post("/auth/logout").cookie(refresh))
                .andExpect(status().isOk());

        // Sedno: nawet mając kopię ciasteczka sprzed wylogowania, nie da się wznowić sesji.
        // Przed dodaniem stanu po stronie serwera taki token działałby jeszcze 7 dni.
        mockMvc.perform(post("/auth/refresh").cookie(refresh))
                .andExpect(status().isUnauthorized());
    }
}
