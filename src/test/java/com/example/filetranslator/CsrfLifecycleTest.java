package com.example.filetranslator;

import com.example.filetranslator.user.model.Role;
import com.example.filetranslator.user.model.User;
import com.example.filetranslator.user.UserRepository;
import com.jayway.jsonpath.JsonPath;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Cykl życia tokenu CSRF u ZALOGOWANEGO użytkownika.
 *
 * Ta klasa świadomie NIE używa .with(csrf()) ze spring-security-test. Tamten post-processor
 * podmienia repozytorium tokenów na własne dla całego kontekstu, więc omija dokładnie tę
 * ścieżkę, której tu pilnujemy: prawdziwe CookieCsrfTokenRepository i wymianę
 * ciasteczko-nagłówek. Wymiana odtworzona jest ręcznie, tak jak robi to frontend -
 * token pobrany z GET /auth/csrf wraca w nagłówku, a ciasteczko dosyła "przeglądarka".
 *
 * Własny @TestPropertySource jest po to, żeby kontekst nie był współdzielony z klasami,
 * które .with(csrf()) wołają - inaczej podmienione repozytorium przeciekłoby i tutaj.
 *
 * Test pilnuje pozycji filtra uwierzytelniającego w łańcuchu: ustawiony przed zarządzaniem sesją
 * bezstanowej aplikacji każde uwierzytelnione żądanie wyglądało na "nowe uwierzytelnienie"
 * i CsrfAuthenticationStrategy podmieniała token. Frontend tracił ważny token po pierwszym
 * żądaniu i dostawał 403 na każdym kolejnym żądaniu zmieniającym stan.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "app.test.context=csrf-lifecycle")
class CsrfLifecycleTest {

    private static final String EMAIL = "csrf@example.com";
    private static final String PASSWORD = "PoprawneHaslo1";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    /** Token CSRF razem z ciasteczkiem, które musi mu towarzyszyć. */
    private record Csrf(String headerName, String token, Cookie cookie) {
    }

    @BeforeEach
    void setUp() {
        userRepository.findByEmail(EMAIL).ifPresent(userRepository::delete);

        User user = new User();
        user.setName("Test CSRF");
        user.setEmail(EMAIL);
        user.setPassword(passwordEncoder.encode(PASSWORD));
        user.setRole(Role.USER);
        user.setEnabled(true);
        userRepository.save(user);
    }

    private Csrf fetchCsrf() throws Exception {
        MvcResult result = mockMvc.perform(get("/auth/csrf"))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        Cookie cookie = cookie(result, "XSRF-TOKEN");

        // Nazwy nagłówka celowo nie sprawdzamy - w kontekście współdzielonym z .with(csrf())
        // byłaby to nazwa z biblioteki testowej. Bierzemy tę, którą podał serwer.
        return new Csrf(JsonPath.read(body, "$.headerName"), JsonPath.read(body, "$.token"), cookie);
    }

    private MvcResult login(Csrf csrf) throws Exception {
        String body = """
                {"email":"%s","password":"%s"}
                """.formatted(EMAIL, PASSWORD);

        return mockMvc.perform(post("/auth/login")
                        .cookie(csrf.cookie())
                        .header(csrf.headerName(), csrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
    }

    private Cookie cookie(MvcResult result, String name) {
        return findCookie(result, name);
    }

    private Cookie findCookie(MvcResult result, String name) {
        return Arrays.stream(result.getResponse().getCookies())
                .filter(c -> name.equals(c.getName()))
                .findFirst()
                .orElse(null);
    }

    @Test
    @DisplayName("Żądanie zalogowanego użytkownika nie podmienia tokenu CSRF")
    void authenticatedRequest_shouldNotReplaceCsrfToken() throws Exception {
        Csrf csrf = fetchCsrf();
        Cookie access = cookie(login(csrf), "accessToken");
        assertThat(access).as("logowanie musi wydać ciasteczko z tokenem dostępu").isNotNull();

        // Zwykły GET, nawet nie zmieniający stanu - wystarczyło, że JwtFilter go uwierzytelnił.
        MvcResult authenticated = mockMvc.perform(get("/actuator/health").cookie(access, csrf.cookie()))
                .andExpect(status().isOk())
                .andReturn();

        // Sedno: odpowiedź nie może przynosić NOWEGO ciasteczka CSRF. Wcześniej przynosiła
        // dwa naraz - kasujące stare i ustawiające inne - a token trzymany przez frontend
        // stawał się w tym momencie bezużyteczny.
        assertThat(findCookie(authenticated, "XSRF-TOKEN"))
                .as("uwierzytelnione żądanie nie może rotować tokenu CSRF")
                .isNull();
    }

    @Test
    @DisplayName("Wylogowanie po wcześniejszym żądaniu zalogowanego użytkownika nadal przechodzi")
    void logoutAfterAuthenticatedRequest_shouldStillPassCsrf() throws Exception {
        Csrf csrf = fetchCsrf();
        Cookie access = cookie(login(csrf), "accessToken");

        // MockMvc nie prowadzi własnego składu ciasteczek, więc rolę przeglądarki trzeba
        // odegrać ręcznie: jeśli odpowiedź przyniosła nowe ciasteczko CSRF, to od tej pory
        // przeglądarka wysyła właśnie je. Nagłówek pozostaje ten sam, bo frontend trzyma
        // token pobrany z /auth/csrf w pamięci i nie ma jak zauważyć podmiany -
        // ciasteczko jest httpOnly. Bez tego kroku test przechodzi nawet z błędem.
        MvcResult authenticated = mockMvc.perform(get("/actuator/health").cookie(access, csrf.cookie()))
                .andExpect(status().isOk())
                .andReturn();

        Cookie current = findCookie(authenticated, "XSRF-TOKEN");
        Cookie csrfCookieNow = current != null ? current : csrf.cookie();

        // To jest żądanie, które przed poprawką wracało z 403 CSRF_TOKEN_INVALID:
        // dla użytkownika oznaczało to, że po zalogowaniu nie da się już wylogować.
        mockMvc.perform(post("/auth/logout")
                        .cookie(access, csrfCookieNow)
                        .header(csrf.headerName(), csrf.token()))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Żądanie bez nagłówka z tokenem nadal jest odrzucane")
    void requestWithoutCsrfHeader_shouldBeRejected() throws Exception {
        Csrf csrf = fetchCsrf();
        Cookie access = cookie(login(csrf), "accessToken");

        // Kontrola przeciwna: ochrona ma dalej działać. Samo ciasteczko nie wystarcza -
        // gdyby wystarczało, cała ochrona CSRF byłaby pozorna, bo ciasteczko przeglądarka
        // dosyła sama także przy żądaniu z obcej strony.
        mockMvc.perform(post("/auth/logout").cookie(access, csrf.cookie()))
                .andExpect(status().isForbidden());
    }
}
