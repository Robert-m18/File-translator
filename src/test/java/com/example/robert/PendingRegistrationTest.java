package com.example.robert;

import com.example.robert.auth.ExpiredTokenCleanupJob;
import com.example.robert.auth.model.PendingRegistration;
import com.example.robert.auth.repository.PendingRegistrationRepository;
import com.example.robert.common.security.TokenHasher;
import com.example.robert.user.UserRepository;
import com.example.robert.user.model.Role;
import com.example.robert.user.model.User;
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

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Rejestracja przez poczekalnię: konto powstaje dopiero przy potwierdzeniu adresu.
 *
 * Najważniejszy test w tej klasie to secondAttempt_shouldNotHijackFirstAttempt - pilnuje
 * dziury, przez którą obca osoba mogła podmienić hasło w cudzej trwającej rejestracji.
 * Poprzedni model (wiersz users z enabled=false, nadpisywany przy ponownej rejestracji)
 * na to pozwalał.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PendingRegistrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PendingRegistrationRepository pendingRegistrationRepository;

    @Autowired
    private ExpiredTokenCleanupJob cleanupJob;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() {
        pendingRegistrationRepository.deleteAll();
        userRepository.deleteAll();
    }

    private void register(String name, String email, String password) throws Exception {
        mockMvc.perform(post("/auth/register")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s","email":"%s","password":"%s"}
                                """.formatted(name, email, password)))
                .andExpect(status().isAccepted());
    }

    private void confirm(String rawToken, int expectedStatus) throws Exception {
        mockMvc.perform(post("/auth/confirm")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"token":"%s"}
                                """.formatted(rawToken)))
                .andExpect(status().is(expectedStatus));
    }

    /** Podstawia znany surowy token do zgłoszenia, żeby test mógł "kliknąć w link". */
    private String pinToken(String email, String rawToken) {
        PendingRegistration pending = pendingRegistrationRepository.findAll().stream()
                .filter(p -> p.getEmail().equals(email))
                .reduce((first, second) -> second)
                .orElseThrow();
        pending.setTokenHash(TokenHasher.sha256Hex(rawToken));
        pendingRegistrationRepository.save(pending);
        return rawToken;
    }

    @Test
    @DisplayName("Rejestracja nie tworzy konta, dopóki adres nie zostanie potwierdzony")
    void register_shouldNotCreateUserBeforeConfirmation() throws Exception {
        register("Adrian", "adrian@example.com", "Haslo12345");

        assertThat(userRepository.count()).isZero();
        assertThat(pendingRegistrationRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("Potwierdzenie zakłada konto od razu aktywne i można się nim zalogować")
    void confirm_shouldCreateEnabledAccount() throws Exception {
        register("Adrian", "adrian@example.com", "Haslo12345");
        confirm(pinToken("adrian@example.com", "token-adriana"), 200);

        User created = userRepository.findByEmail("adrian@example.com").orElseThrow();
        // enabled=true od razu - wiersz users z enabled=false jest teraz stanem niemożliwym
        assertThat(created.isEnabled()).isTrue();
        assertThat(created.getName()).isEqualTo("Adrian");
        assertThat(pendingRegistrationRepository.count()).isZero();

        mockMvc.perform(post("/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"adrian@example.com","password":"Haslo12345"}
                                """))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Drugie zgłoszenie na ten sam adres nie przejmuje pierwszego")
    void secondAttempt_shouldNotHijackFirstAttempt() throws Exception {
        // Ofiara się rejestruje
        register("Ofiara", "ofiara@example.com", "HasloOfiary1");
        String tokenOfiary = pinToken("ofiara@example.com", "token-ofiary");

        // Napastnik zgłasza rejestrację na ten sam adres, ze swoim hasłem
        register("Napastnik", "ofiara@example.com", "HasloNapastnika9");

        // Oba zgłoszenia żyją obok siebie - nic nie zostało nadpisane
        assertThat(pendingRegistrationRepository.count()).isEqualTo(2);

        // Ofiara klika SWÓJ link
        confirm(tokenOfiary, 200);

        User account = userRepository.findByEmail("ofiara@example.com").orElseThrow();
        assertThat(account.getName()).isEqualTo("Ofiara");

        // Sedno: konto ma hasło OFIARY, nie napastnika
        assertThat(passwordEncoder.matches("HasloOfiary1", account.getPassword())).isTrue();
        assertThat(passwordEncoder.matches("HasloNapastnika9", account.getPassword())).isFalse();

        // Zgłoszenie napastnika zostało skasowane razem z resztą - nie da się go już użyć
        assertThat(pendingRegistrationRepository.count()).isZero();
    }

    @Test
    @DisplayName("Rejestracja na zajęty adres nie zdradza, że konto istnieje")
    void register_onTakenEmail_shouldNotLeakExistence() throws Exception {
        User existing = new User();
        existing.setName("Właściciel");
        existing.setEmail("zajety@example.com");
        existing.setPassword(passwordEncoder.encode("PrawdziweHaslo1"));
        existing.setRole(Role.USER);
        existing.setEnabled(true);
        userRepository.save(existing);

        // Ta sama odpowiedź co przy adresie wolnym: 202, bez 409 i bez EMAIL_ALREADY_EXISTS
        register("Podszywacz", "zajety@example.com", "InneHaslo9");

        // Nic nie powstało i hasło właściciela zostało nietknięte
        assertThat(pendingRegistrationRepository.count()).isZero();
        assertThat(userRepository.count()).isEqualTo(1);
        User after = userRepository.findByEmail("zajety@example.com").orElseThrow();
        assertThat(passwordEncoder.matches("PrawdziweHaslo1", after.getPassword())).isTrue();
    }

    @Test
    @DisplayName("Ponowne kliknięcie zużytego linku mówi, że mógł już zostać wykorzystany")
    void confirm_replayedToken_shouldNotReadAsForgedLink() throws Exception {
        register("Adrian", "adrian@example.com", "Haslo12345");
        String token = pinToken("adrian@example.com", "token-adriana");
        confirm(token, 200);

        /*
         * To samo żądanie drugi raz - tak wygląda odświeżenie strony potwierdzenia, więc
         * jest to najczęstsza droga do tej odpowiedzi, a nie przypadek brzegowy. Kod zostaje
         * INVALID_TOKEN, bo serwer naprawdę nie umie odróżnić zużytego linku od zmyślonego;
         * zmienia się to, co czyta użytkownik.
         *
         * Wyjątkowo asercja na TREŚĆ komunikatu, choć konwencja każe frontom rozgałwiać się
         * po "code": naprawą jest właśnie treść, więc test sprawdzający sam kod przechodziłby
         * identycznie przed i po zmianie, czyli nie pilnowałby niczego.
         */
        mockMvc.perform(post("/auth/confirm")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"token":"%s"}
                                """.formatted(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TOKEN"))
                .andExpect(jsonPath("$.detail").value(containsString("wykorzystany")));

        // Konto z pierwszego potwierdzenia jest nietknięte - powtórka niczego nie psuje
        assertThat(userRepository.findByEmail("adrian@example.com")).isPresent();
    }

    @Test
    @DisplayName("Wygasłe zgłoszenie nie aktywuje konta")
    void confirm_shouldRejectExpiredPendingRegistration() throws Exception {
        pendingRegistrationRepository.save(new PendingRegistration(
                "spozniony@example.com", "Spozniony", passwordEncoder.encode("Haslo12345"),
                TokenHasher.sha256Hex("stary-token"),
                LocalDateTime.now().minusHours(48), LocalDateTime.now().minusHours(24)));

        confirm("stary-token", 410); // 410 Gone - TokenExpiredException

        assertThat(userRepository.count()).isZero();
    }

    @Test
    @DisplayName("Sprzątanie usuwa porzucone zgłoszenia, oszczędzając świeże")
    void cleanup_shouldRemoveOnlyExpiredPendingRegistrations() {
        pendingRegistrationRepository.save(new PendingRegistration(
                "porzucony@example.com", "Porzucony", "hash", TokenHasher.sha256Hex("t1"),
                LocalDateTime.now().minusHours(48), LocalDateTime.now().minusHours(24)));
        pendingRegistrationRepository.save(new PendingRegistration(
                "swiezy@example.com", "Swiezy", "hash", TokenHasher.sha256Hex("t2"),
                LocalDateTime.now(), LocalDateTime.now().plusHours(24)));

        cleanupJob.cleanupExpiredTokens();

        assertThat(pendingRegistrationRepository.count()).isEqualTo(1);
        assertThat(pendingRegistrationRepository.findByTokenHash(TokenHasher.sha256Hex("t2"))).isPresent();
    }
}
