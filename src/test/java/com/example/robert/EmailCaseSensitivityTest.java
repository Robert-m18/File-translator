package com.example.robert;

import com.example.robert.auth.repository.PendingRegistrationRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regresja na przejście z MySQL-a na PostgreSQL.
 *
 * MySQL przy domyślnym collation porównywał teksty BEZ rozróżniania wielkości liter,
 * więc zapytanie o "Robert@Example.com" znajdowało wiersz zapisany małymi literami.
 * PostgreSQL rozróżnia. Bez normalizacji adresu (EmailNormalizer) użytkownik, który
 * zarejestrował się wielkimi literami i wraca wpisując małe, dostaje BAD_CREDENTIALS -
 * nie do odróżnienia od złego hasła, bo API celowo tych przypadków nie rozróżnia,
 * więc sam nigdy z tego nie wyjdzie.
 *
 * Te testy padają, jeśli normalizacja zniknie z konstruktorów kompaktowych DTO.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EmailCaseSensitivityTest {

    private static final String EMAIL = "wielkosc.liter@example.com";
    private static final String PASSWORD = "PoprawneHaslo1";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private PendingRegistrationRepository pendingRegistrationRepository;

    @BeforeEach
    void setUp() {
        userRepository.findByEmail(EMAIL).ifPresent(userRepository::delete);

        User user = new User();
        user.setName("Test Wielkości Liter");
        user.setEmail(EMAIL);
        user.setPassword(passwordEncoder.encode(PASSWORD));
        user.setRole(Role.USER);
        user.setEnabled(true);
        userRepository.save(user);
    }

    private int loginStatus(String email) throws Exception {
        String body = """
                {"email":"%s","password":"%s"}
                """.formatted(email, PASSWORD);

        return mockMvc.perform(post("/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    @Test
    @DisplayName("Logowanie działa niezależnie od wielkości liter w adresie")
    void login_shouldIgnoreEmailCase() throws Exception {
        assertThat(loginStatus(EMAIL)).isEqualTo(200);
        assertThat(loginStatus(EMAIL.toUpperCase())).isEqualTo(200);
        assertThat(loginStatus("Wielkosc.Liter@Example.COM")).isEqualTo(200);
    }

    @Test
    @DisplayName("Białe znaki na brzegach adresu nie blokują logowania")
    void login_shouldTrimEmail() throws Exception {
        assertThat(loginStatus("  " + EMAIL + "  ")).isEqualTo(200);
    }

    /**
     * Druga strona tej samej monety: gdyby adresy trafiały do bazy w postaci, w jakiej
     * je wpisano, unikat uk_users_email przestałby zapobiegać dwóm kontom na ten sam
     * adres pisany różną wielkością liter. To już nie niewygoda, tylko dziura
     * w modelu tożsamości.
     */
    @Test
    @DisplayName("Rejestracja zapisuje zgłoszenie z adresem w postaci kanonicznej")
    void register_shouldStoreNormalizedEmail() throws Exception {
        // Bez sprzątania przed: pending_registrations.email celowo nie ma unikatu, więc
        // kolejne przebiegi tylko dokładają wiersz, a obie asercje niżej dalej trzymają.
        String body = """
                {"name":"Nowy","email":"NOWY.Adres@Example.COM","password":"%s"}
                """.formatted(PASSWORD);

        mockMvc.perform(post("/auth/register")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted());

        // Konto powstaje dopiero po potwierdzeniu, więc adres sprawdzamy tam, gdzie
        // faktycznie wylądował - w poczekalni. To ta wartość trafi potem wprost
        // do users.email, czyli ona decyduje, czy unikat uk_users_email cokolwiek chroni.
        assertThat(pendingRegistrationRepository.findAll())
                .extracting("email")
                .contains("nowy.adres@example.com")
                .doesNotContain("NOWY.Adres@Example.COM");
    }
}
