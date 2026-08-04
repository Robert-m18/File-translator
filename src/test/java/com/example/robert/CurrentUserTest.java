package com.example.robert;

import com.example.robert.common.security.JwtUtil;
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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /auth/me - pierwszy endpoint w tej aplikacji wymagający zalogowania.
 *
 * Testujemy tu dwie rzeczy naraz, bo obie są konsekwencją jego istnienia: że zalogowany
 * dostaje swoje dane (i tylko swoje), oraz że niezalogowany dostaje kontrolowane 401,
 * a nie 500 z pustego principala.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CurrentUserTest {

    private static final String EMAIL = "biezacy@example.com";
    private static final String PASSWORD = "PoprawneHaslo1";
    private static final String NAME = "Test Biezacego";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtUtil jwtUtil;

    @BeforeEach
    void setUp() {
        userRepository.findByEmail(EMAIL).ifPresent(userRepository::delete);

        User user = new User();
        user.setName(NAME);
        user.setEmail(EMAIL);
        user.setPassword(passwordEncoder.encode(PASSWORD));
        user.setRole(Role.USER);
        user.setEnabled(true);
        userRepository.save(user);
    }

    private Cookie loginAndGetAccessCookie() throws Exception {
        String body = """
                {"email":"%s","password":"%s"}
                """.formatted(EMAIL, PASSWORD);

        MvcResult result = mockMvc.perform(post("/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();

        return Arrays.stream(result.getResponse().getCookies())
                .filter(c -> "accessToken".equals(c.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Logowanie nie ustawiło ciasteczka accessToken"));
    }

    @Test
    @DisplayName("Zalogowany dostaje swoje dane")
    void me_shouldReturnCurrentUser_whenAuthenticated() throws Exception {
        Long expectedId = userRepository.findByEmail(EMAIL).orElseThrow().getId();

        mockMvc.perform(get("/auth/me").cookie(loginAndGetAccessCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(expectedId))
                .andExpect(jsonPath("$.name").value(NAME))
                .andExpect(jsonPath("$.email").value(EMAIL))
                .andExpect(jsonPath("$.role").value("USER"));
    }

    /**
     * Asercja idzie po surowej treści odpowiedzi, a nie po kształcie DTO. Chodzi o kontrakt
     * widziany przez przeglądarkę: gdyby ktoś kiedyś zwrócił tu encję User zamiast rekordu,
     * test na polach DTO dalej byłby zielony, a hash hasła pojechałby do klienta.
     */
    @Test
    @DisplayName("Odpowiedź nie wynosi hasła ani stanu zabezpieczeń konta")
    void me_shouldNotExposeSensitiveFields() throws Exception {
        String body = mockMvc.perform(get("/auth/me").cookie(loginAndGetAccessCookie()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body)
                .doesNotContain("password")
                .doesNotContain("enabled")
                .doesNotContain("failedLoginAttempts")
                .doesNotContain("lockedUntil")
                .doesNotContain("authorities");
    }

    /**
     * Regresja na kolejność reguł w SecurityConfig. Bez wpisu dla /auth/me PRZED
     * PUBLIC_ENDPOINTS ścieżka wpada w permitAll dla /auth/**, żądanie dochodzi do
     * kontrolera z pustym principalem i kończy się 500 zamiast 401.
     */
    @Test
    @DisplayName("Bez ciasteczka wraca 401 w formacie ProblemDetail, nie 500")
    void me_shouldReturn401_whenNoCookie() throws Exception {
        mockMvc.perform(get("/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").isNotEmpty());
    }

    /**
     * Regresja na kontrolę claimu "type" w JwtFilter. Token odświeżający ma poprawny podpis,
     * więc bez tej kontroli uwierzytelniłby użytkownika - a że filtr nie sprawdza bazy,
     * działałby także po wylogowaniu, przez pełne 7 dni ważności.
     */
    @Test
    @DisplayName("Token odświeżający podstawiony pod accessToken nie uwierzytelnia")
    void me_shouldReturn401_whenRefreshTokenUsedAsAccess() throws Exception {
        Cookie podstawiony = new Cookie("accessToken", jwtUtil.generateRefreshToken(EMAIL));

        mockMvc.perform(get("/auth/me").cookie(podstawiony))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_TOKEN_TYPE"));
    }
}
