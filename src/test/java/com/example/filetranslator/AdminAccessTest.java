package com.example.filetranslator;

import com.example.filetranslator.user.AdminBootstrap;
import com.example.filetranslator.user.UserRepository;
import com.example.filetranslator.user.model.Role;
import com.example.filetranslator.user.model.User;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Konto administratora zakładane przy starcie i dostęp do aktuatora.
 *
 * Testujemy przez metodę AdminBootstrap.createAdminIfMissing, a nie przez włączenie
 * bootstrapu własnym @TestPropertySource. Powód jest praktyczny: osobny kontekst kosztuje
 * kilka sekund budowania, a konto założone raz przy jego starcie byłoby zależne od
 * kolejności klas testowych - baza H2 jest wspólna dla wszystkich kontekstów w JVM,
 * a trzy inne klasy robią userRepository.deleteAll() w @BeforeEach.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminAccessTest {

    private static final String ADMIN_EMAIL = "admin-test@example.com";
    private static final String ADMIN_PASSWORD = "AdminHaslo1";
    private static final String ADMIN_NAME = "Administrator";

    private static final String USER_EMAIL = "zwykly-test@example.com";
    private static final String USER_PASSWORD = "ZwykleHaslo1";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AdminBootstrap adminBootstrap;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() {
        userRepository.findByEmail(ADMIN_EMAIL).ifPresent(userRepository::delete);
        userRepository.findByEmail(USER_EMAIL).ifPresent(userRepository::delete);
    }

    private Cookie loginAndGetAccessCookie(String email, String password) throws Exception {
        String body = """
                {"email":"%s","password":"%s"}
                """.formatted(email, password);

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

    private void createZwyklyUser() {
        User user = new User();
        user.setName("Zwykły Użytkownik");
        user.setEmail(USER_EMAIL);
        user.setPassword(passwordEncoder.encode(USER_PASSWORD));
        user.setRole(Role.USER);
        user.setEnabled(true);
        userRepository.save(user);
    }

    /**
     * Właściwy cel całej zmiany: rola ADMIN ma być osiągalna, a więc metryki mają dać się
     * obejrzeć po zalogowaniu. Idzie przez prawdziwy łańcuch filtrów, bo tylko tak sprawdza
     * się, że ciasteczkowe uwierzytelnienie działa również poza /auth/**.
     */
    @Test
    @DisplayName("Administrator po zalogowaniu widzi metryki i prometheusa")
    void admin_shouldReachActuatorEndpoints() throws Exception {
        adminBootstrap.createAdminIfMissing(ADMIN_EMAIL, ADMIN_NAME, ADMIN_PASSWORD);
        Cookie ciasteczko = loginAndGetAccessCookie(ADMIN_EMAIL, ADMIN_PASSWORD);

        mockMvc.perform(get("/actuator/metrics").cookie(ciasteczko))
                .andExpect(status().isOk());

        mockMvc.perform(get("/actuator/prometheus").cookie(ciasteczko))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Konto zakładane jest z rolą ADMIN i od razu włączone")
    void bootstrap_shouldCreateEnabledAdmin() {
        adminBootstrap.createAdminIfMissing(ADMIN_EMAIL, ADMIN_NAME, ADMIN_PASSWORD);

        User admin = userRepository.findByEmail(ADMIN_EMAIL).orElseThrow();
        assertThat(admin.getRole()).isEqualTo(Role.ADMIN);
        assertThat(admin.isEnabled()).isTrue();
        assertThat(passwordEncoder.matches(ADMIN_PASSWORD, admin.getPassword())).isTrue();
    }

    /**
     * Regresja na dwie reguły naraz, i obie trzeba sprawdzać razem.
     *
     * Gdyby bootstrap podnosił rolę istniejącego konta, literówka w app.admin.email oddawałaby
     * konto prawdziwego użytkownika razem z dostępem do /actuator/**. Gdyby nadpisywał hasło,
     * każdy restart cofałby rotację hasła administratora. Asercja tylko na roli przepuściłaby
     * wersję po cichu nadpisującą hasło.
     */
    @Test
    @DisplayName("Istniejące konto USER nie awansuje na ADMIN i nie traci hasła")
    void bootstrap_shouldNotPromoteExistingUser() {
        User przed = new User();
        przed.setName("Ktoś Wcześniejszy");
        przed.setEmail(ADMIN_EMAIL);
        przed.setPassword(passwordEncoder.encode(USER_PASSWORD));
        przed.setRole(Role.USER);
        przed.setEnabled(true);
        userRepository.save(przed);

        adminBootstrap.createAdminIfMissing(ADMIN_EMAIL, ADMIN_NAME, ADMIN_PASSWORD);

        User po = userRepository.findByEmail(ADMIN_EMAIL).orElseThrow();
        assertThat(po.getRole()).isEqualTo(Role.USER);
        assertThat(passwordEncoder.matches(USER_PASSWORD, po.getPassword())).isTrue();
    }

    /**
     * Ponowne wywołanie ma być bezczynne. W praktyce to każdy restart aplikacji:
     * konto ma zostać jedno, z niezmienionym hasłem.
     */
    @Test
    @DisplayName("Powtórne wywołanie nie zakłada drugiego konta ani nie zmienia hasła")
    void bootstrap_shouldBeIdempotent() {
        adminBootstrap.createAdminIfMissing(ADMIN_EMAIL, ADMIN_NAME, ADMIN_PASSWORD);
        Long id = userRepository.findByEmail(ADMIN_EMAIL).orElseThrow().getId();

        adminBootstrap.createAdminIfMissing(ADMIN_EMAIL, ADMIN_NAME, "InneHaslo9");

        User admin = userRepository.findByEmail(ADMIN_EMAIL).orElseThrow();
        assertThat(admin.getId()).isEqualTo(id);
        assertThat(passwordEncoder.matches(ADMIN_PASSWORD, admin.getPassword())).isTrue();
    }

    /**
     * Kontrakt fail-fast: hasło niespełniające polityki rejestracji ma zatrzymać start
     * aplikacji, a nie założyć słabe konto z dostępem do aktuatora. Test pilnuje też,
     * żeby komunikat nie wyniósł jawnego hasła - ten wyjątek kończy start, więc jego treść
     * ląduje w konsoli i w zbieraczu logów.
     */
    @Test
    @DisplayName("Hasło niezgodne z polityką zatrzymuje start i nie wycieka do komunikatu")
    void bootstrap_shouldRejectWeakPassword() {
        assertThatThrownBy(() -> adminBootstrap.createAdminIfMissing(ADMIN_EMAIL, ADMIN_NAME, "abc"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("password")
                .hasMessageNotContaining("abc");

        assertThat(userRepository.findByEmail(ADMIN_EMAIL)).isEmpty();
    }

    /**
     * Zalogowany, ale z niewłaściwą rolą - gałąź, której do tej pory nie dało się przejść,
     * bo nikt nigdy nie miał roli ADMIN, więc reguła hasRole("ADMIN") odcinała wszystkich
     * na 401 jeszcze przed rozróżnieniem "kim jesteś" od "czy wolno ci". ApiContractTest
     * pokrywa anonima (401); to jest ta druga strona, obsługiwana przez RestAccessDeniedHandler.
     */
    @Test
    @DisplayName("Zwykły użytkownik dostaje 403 ACCESS_DENIED, nie 401")
    void user_shouldGet403OnActuator() throws Exception {
        createZwyklyUser();

        mockMvc.perform(get("/actuator/metrics").cookie(loginAndGetAccessCookie(USER_EMAIL, USER_PASSWORD)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }
}
