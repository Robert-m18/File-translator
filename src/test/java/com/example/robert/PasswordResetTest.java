package com.example.robert;

import com.example.robert.auth.model.PasswordResetToken;
import com.example.robert.auth.repository.PasswordResetTokenRepository;
import com.example.robert.common.security.TokenHasher;
import com.example.robert.user.UserRepository;
import com.example.robert.user.model.Role;
import com.example.robert.user.model.User;
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

import java.time.LocalDateTime;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Ścieżka resetu hasła.
 *
 * Poza samą zmianą hasła sprawdza dwie rzeczy, które łatwo pominąć, a bez których reset
 * jest pozorny: unieważnienie istniejących sesji (napastnik z ważnym tokenem odświeżającym
 * musi wypaść z konta) i jednorazowość linku.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PasswordResetTest {

    private static final String EMAIL = "reset@example.com";
    private static final String OLD_PASSWORD = "StareHaslo1";
    private static final String NEW_PASSWORD = "NoweHaslo9";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordResetTokenRepository passwordResetTokenRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() {
        passwordResetTokenRepository.deleteAll();
        userRepository.deleteAll();

        User user = new User();
        user.setName("Reset Test");
        user.setEmail(EMAIL);
        user.setPassword(passwordEncoder.encode(OLD_PASSWORD));
        user.setRole(Role.USER);
        user.setEnabled(true);
        userRepository.save(user);
    }

    private void forgotPassword(String email) throws Exception {
        mockMvc.perform(post("/auth/forgot-password")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s"}
                                """.formatted(email)))
                .andExpect(status().isOk());
    }

    /** Podstawia znany surowy token do najnowszego wpisu, żeby test mógł "kliknąć w link". */
    private String pinToken(String rawToken) {
        PasswordResetToken token = passwordResetTokenRepository.findAll().stream()
                .filter(t -> !t.isUsed())
                .reduce((first, second) -> second)
                .orElseThrow();
        token.setTokenHash(TokenHasher.sha256Hex(rawToken));
        passwordResetTokenRepository.save(token);
        return rawToken;
    }

    private MvcResult resetPassword(String rawToken, String newPassword) throws Exception {
        return mockMvc.perform(post("/auth/reset-password")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"token":"%s","password":"%s"}
                                """.formatted(rawToken, newPassword)))
                .andReturn();
    }

    private int login(String password) throws Exception {
        return mockMvc.perform(post("/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}
                                """.formatted(EMAIL, password)))
                .andReturn().getResponse().getStatus();
    }

    @Test
    @DisplayName("Reset zmienia hasło: stare przestaje działać, nowe działa")
    void reset_shouldReplacePassword() throws Exception {
        forgotPassword(EMAIL);
        assertThat(resetPassword(pinToken("moj-token"), NEW_PASSWORD).getResponse().getStatus())
                .isEqualTo(200);

        assertThat(login(OLD_PASSWORD)).isEqualTo(401);
        assertThat(login(NEW_PASSWORD)).isEqualTo(200);
    }

    @Test
    @DisplayName("Reset unieważnia wszystkie istniejące sesje")
    void reset_shouldRevokeExistingSessions() throws Exception {
        // Sesja sprzed resetu - taką ma napastnik, jeśli powodem resetu było przejęcie konta
        MvcResult loginResult = mockMvc.perform(post("/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}
                                """.formatted(EMAIL, OLD_PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();

        Cookie refreshCookie = Arrays.stream(loginResult.getResponse().getCookies())
                .filter(c -> "refreshToken".equals(c.getName()))
                .findFirst()
                .orElseThrow();

        forgotPassword(EMAIL);
        resetPassword(pinToken("moj-token"), NEW_PASSWORD);

        // Token odświeżający sprzed resetu jest martwy - bez tego zmiana hasła
        // nie odbierałaby napastnikowi dostępu przez kolejne 7 dni
        mockMvc.perform(post("/auth/refresh").with(csrf()).cookie(refreshCookie))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Link do resetu działa tylko raz")
    void reset_shouldRejectSecondUseOfSameLink() throws Exception {
        forgotPassword(EMAIL);
        String token = pinToken("moj-token");

        assertThat(resetPassword(token, NEW_PASSWORD).getResponse().getStatus()).isEqualTo(200);

        mockMvc.perform(post("/auth/reset-password")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"token":"%s","password":"JeszczeInne7"}
                                """.formatted(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TOKEN"));

        // Hasło z pierwszego resetu obowiązuje dalej
        assertThat(login(NEW_PASSWORD)).isEqualTo(200);
    }

    @Test
    @DisplayName("Nowe żądanie resetu unieważnia poprzedni link")
    void newRequest_shouldInvalidatePreviousToken() throws Exception {
        forgotPassword(EMAIL);
        String pierwszy = pinToken("pierwszy-token");

        forgotPassword(EMAIL);
        pinToken("drugi-token");

        // Stary link nie działa - w obiegu ma być najwyżej jeden żywy klucz do konta
        mockMvc.perform(post("/auth/reset-password")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"token":"%s","password":"%s"}
                                """.formatted(pierwszy, NEW_PASSWORD)))
                .andExpect(status().isBadRequest());

        assertThat(login(OLD_PASSWORD)).isEqualTo(200);
    }

    @Test
    @DisplayName("Wygasły link zwraca 410")
    void reset_shouldRejectExpiredToken() throws Exception {
        User user = userRepository.findByEmail(EMAIL).orElseThrow();
        passwordResetTokenRepository.save(new PasswordResetToken(
                TokenHasher.sha256Hex("wygasly-token"), user,
                LocalDateTime.now().minusHours(2), LocalDateTime.now().minusHours(1)));

        mockMvc.perform(post("/auth/reset-password")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"token":"wygasly-token","password":"%s"}
                                """.formatted(NEW_PASSWORD)))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("TOKEN_EXPIRED"));
    }

    @Test
    @DisplayName("Reset egzekwuje politykę hasła tak samo jak rejestracja")
    void reset_shouldEnforcePasswordPolicy() throws Exception {
        forgotPassword(EMAIL);
        String token = pinToken("moj-token");

        // "abc" - za krótkie i bez cyfry. Gdyby polityka była skopiowana zamiast wyniesionej
        // do @ValidPassword, reset mógłby po cichu przyjmować słabsze hasła niż rejestracja.
        mockMvc.perform(post("/auth/reset-password")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"token":"%s","password":"abc"}
                                """.formatted(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        assertThat(login(OLD_PASSWORD)).isEqualTo(200);
    }

    @Test
    @DisplayName("Żądanie resetu nie zdradza, czy adres istnieje")
    void forgotPassword_shouldNotLeakAccountExistence() throws Exception {
        MvcResult znany = mockMvc.perform(post("/auth/forgot-password")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s"}
                                """.formatted(EMAIL)))
                .andExpect(status().isOk())
                .andReturn();

        MvcResult nieznany = mockMvc.perform(post("/auth/forgot-password")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"nieznany@example.com"}
                                """))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(message(znany)).isEqualTo(message(nieznany));

        // Dla nieznanego adresu nie powstał żaden token
        assertThat(passwordResetTokenRepository.count()).isEqualTo(1);
    }

    private String message(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString()
                .replaceAll("\"timestamp\":\"[^\"]*\"", "");
    }
}
