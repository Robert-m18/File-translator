package com.example.filetranslator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.blankOrNullString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Testy kontraktu API na poziomie HTTP - sprawdzają rzeczy, których testy jednostkowe
 * nie dotykają, bo wynikają dopiero ze złożenia konfiguracji: łańcucha filtrów Spring
 * Security, globalnego handlera wyjątków i walidacji.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApiContractTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("Żądanie bez tokenu do chronionego zasobu zwraca 401 w formacie ProblemDetail")
    void protectedEndpointWithoutToken_shouldReturnProblemDetail401() throws Exception {
        // /actuator/metrics, a nie /users - to drugie nie ma już kontrolera, więc test
        // sprawdzałby regułę autoryzacji dla ścieżki bez handlera.
        mockMvc.perform(get("/actuator/metrics"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.title").value(notNullValue()))
                .andExpect(jsonPath("$.timestamp").value(notNullValue()));
    }

    @Test
    @DisplayName("Każda odpowiedź niesie nagłówek korelacji, a podany przez klienta jest zachowywany")
    void response_shouldCarryTraceIdHeader() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-Id", notNullValue()));

        mockMvc.perform(get("/actuator/health").header("X-Request-Id", "moj-trace-id"))
                .andExpect(header().string("X-Request-Id", "moj-trace-id"));
    }

    @Test
    @DisplayName("Rejestracja ze słabym hasłem zwraca 400 z listą błędów per pole")
    void register_withWeakPassword_shouldReturnFieldErrors() throws Exception {
        // "abc" - za krótkie i bez cyfry. Przed poprawką takie hasło przechodziło,
        // bo jedyna reguła (@Size(min=8)) stała na encji i sprawdzała hash BCrypt.
        String body = """
                {"name":"Robert","email":"robert@example.com","password":"abc"}
                """;

        mockMvc.perform(post("/auth/register")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors", hasSize(2)))
                .andExpect(jsonPath("$.errors[*].field").value(org.hamcrest.Matchers.hasItem("password")));
    }

    @Test
    @DisplayName("Rejestracja z niepoprawnym adresem email jest odrzucana")
    void register_withInvalidEmail_shouldReturn400() throws Exception {
        String body = """
                {"name":"Robert","email":"to-nie-jest-email","password":"Haslo1234"}
                """;

        mockMvc.perform(post("/auth/register")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field").value(org.hamcrest.Matchers.hasItem("email")));
    }

    @Test
    @DisplayName("Logowanie na nieistniejące konto zwraca 401 bez zdradzania, czy email istnieje")
    void login_withUnknownUser_shouldReturnGenericProblem() throws Exception {
        String body = """
                {"email":"nieistnieje@example.com","password":"Haslo1234"}
                """;

        mockMvc.perform(post("/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("BAD_CREDENTIALS"))
                // Komunikat nie może rozróżniać "nie ma takiego użytkownika" od "złe hasło"
                .andExpect(jsonPath("$.detail").value(not(org.hamcrest.Matchers.containsString("nieistnieje"))));
    }

    @Test
    @DisplayName("Niezalogowany ma dostęp do health checku, ale nie do metryk")
    void actuator_shouldExposeOnlyHealthPublicly() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/actuator/metrics"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Żądanie zmieniające stan bez tokenu CSRF jest odrzucane z własnym kodem")
    void stateChangingRequestWithoutCsrfToken_shouldReturn403() throws Exception {
        // Bez .with(csrf()) - dokładnie tak wygląda żądanie wysłane przez obcą stronę.
        // Kod CSRF_TOKEN_INVALID, a nie ACCESS_DENIED: front ma pobrać nowy token
        // i powtórzyć żądanie, a nie wyrzucać użytkownika.
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"ktokolwiek@example.com","password":"Haslo1234"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("CSRF_TOKEN_INVALID"));
    }

    @Test
    @DisplayName("Token CSRF jest wydawany w ciele odpowiedzi, nie tylko w ciasteczku")
    void csrfEndpoint_shouldReturnTokenInBody() throws Exception {
        /*
         * Wartość musi być w ciele, bo przy froncie na innej domenie JavaScript nie ma
         * jak odczytać ciasteczka wystawionego przez API - i to jest tu sprawdzane.
         *
         * Konkretnej nazwy nagłówka świadomie NIE sprawdzamy. spring-security-test przy
         * pierwszym użyciu .with(csrf()) podstawia własne repozytorium tokenów na cały
         * kontekst serwletu, więc od tej chwili endpoint zwraca nazwę z biblioteki
         * testowej, a nie z konfiguracji aplikacji. Asercja na "X-XSRF-TOKEN" przechodziłaby
         * w izolacji i wywalała się w pełnym przebiegu, zależnie od kolejności klas.
         */
        mockMvc.perform(get("/auth/csrf"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value(notNullValue()))
                .andExpect(jsonPath("$.headerName").value(not(blankOrNullString())));
    }

    @Test
    @DisplayName("Specyfikacja OpenAPI jest generowana i publicznie dostępna")
    void openApiDocs_shouldBeAvailable() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi").value(notNullValue()))
                .andExpect(jsonPath("$.paths['/auth/login']").value(notNullValue()));
    }
}
