package com.example.robert;

import com.example.robert.user.model.Role;
import com.example.robert.user.model.User;
import com.example.robert.user.UserRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Test blokady konta po serii nieudanych logowań.
 *
 * Celowo przez HTTP, a nie na samym serwisie: sprawdzana ścieżka przechodzi przez
 * zdarzenia Spring Security (AuthenticationFailureBadCredentialsEvent), listener,
 * atomowy UPDATE licznika i User.isAccountNonLocked(). Test jednostkowy na
 * LoginAttemptService potwierdziłby tylko, że liczy do trzech - a nie, że całość
 * jest poprawnie spięta.
 *
 * Profil testowy ustawia max-attempts na 3.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AccountLockoutTest {

    private static final String EMAIL = "lockout@example.com";
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
        user.setName("Lockout Test");
        user.setEmail(EMAIL);
        user.setPassword(passwordEncoder.encode(PASSWORD));
        user.setRole(Role.USER);
        user.setEnabled(true);
        userRepository.save(user);
    }

    private int attemptLogin(String password) throws Exception {
        String body = """
                {"email":"%s","password":"%s"}
                """.formatted(EMAIL, password);

        return mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn().getResponse().getStatus();
    }

    @Test
    @DisplayName("Konto blokuje się po przekroczeniu progu nieudanych logowań")
    void accountShouldLockAfterConfiguredFailures() throws Exception {
        // Pierwsze próby to zwykłe 401 - konto jeszcze działa
        assertThat(attemptLogin("ZleHaslo1")).isEqualTo(401);
        assertThat(attemptLogin("ZleHaslo1")).isEqualTo(401);

        // Trzecia próba przekracza próg i zamyka konto
        assertThat(attemptLogin("ZleHaslo1")).isEqualTo(401);

        assertThat(userRepository.findByEmail(EMAIL).orElseThrow().getLockedUntil()).isNotNull();

        // Od tej chwili nawet POPRAWNE hasło nie przechodzi - to jest sedno blokady.
        // Bez niej napastnik mógłby zgadywać hasło w nieskończoność.
        String body = """
                {"email":"%s","password":"%s"}
                """.formatted(EMAIL, PASSWORD);

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));
    }

    @Test
    @DisplayName("Udane logowanie zeruje licznik nieudanych prób")
    void successfulLoginShouldResetCounter() throws Exception {
        assertThat(attemptLogin("ZleHaslo1")).isEqualTo(401);
        assertThat(attemptLogin("ZleHaslo1")).isEqualTo(401);
        assertThat(userRepository.findByEmail(EMAIL).orElseThrow().getFailedLoginAttempts()).isEqualTo(2);

        // Poprawne logowanie
        assertThat(attemptLogin(PASSWORD)).isEqualTo(200);

        // Licznik wyzerowany - inaczej konto używane latami zablokowałoby się
        // po zsumowaniu przypadkowych literówek z wielu miesięcy
        User user = userRepository.findByEmail(EMAIL).orElseThrow();
        assertThat(user.getFailedLoginAttempts()).isZero();
        assertThat(user.getLockedUntil()).isNull();
    }
}
