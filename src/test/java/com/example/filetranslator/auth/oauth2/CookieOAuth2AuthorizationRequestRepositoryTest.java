package com.example.filetranslator.auth.oauth2;


import com.example.filetranslator.common.security.CookieProperties;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test jednostkowy (BEZ kontekstu Springa) dla repozytorium trzymającego żądanie
 * autoryzacyjne OAuth2 w ciasteczku.
 *
 * Sprawdza to, czego nie sprawdza żaden inny test w projekcie: że to, co zapisze
 * saveAuthorizationRequest, da się odtworzyć przez loadAuthorizationRequest -
 * czyli że serializacja/deserializacja Jacksonem faktycznie działa, a nie tylko
 * się kompiluje.
 */
class CookieOAuth2AuthorizationRequestRepositoryTest {

    private CookieOAuth2AuthorizationRequestRepository repository;

    @BeforeEach
    void setUp() {
        // Mock zamiast prawdziwego CookieProperties - w tym teście liczy się tylko
        // wartość flagi Secure, reszta pól CookieProperties jest tu nieistotna.
        CookieProperties cookieProperties = Mockito.mock(CookieProperties.class);
        Mockito.when(cookieProperties.secure()).thenReturn(true);
        repository = new CookieOAuth2AuthorizationRequestRepository(cookieProperties);
    }

    @Test
    @DisplayName("Zapisane żądanie da się odtworzyć z ciasteczka bez utraty danych")
    void saveThenLoad_shouldReturnEquivalentRequest() {
        OAuth2AuthorizationRequest original = sampleRequest();

        MockHttpServletResponse saveResponse = new MockHttpServletResponse();
        repository.saveAuthorizationRequest(original, new MockHttpServletRequest(), saveResponse);

        MockHttpServletRequest loadRequest = requestWithCookieFrom(saveResponse);
        OAuth2AuthorizationRequest loaded = repository.loadAuthorizationRequest(loadRequest);

        assertThat(loaded).isNotNull();
        assertThat(loaded.getAuthorizationUri()).isEqualTo(original.getAuthorizationUri());
        assertThat(loaded.getClientId()).isEqualTo(original.getClientId());
        assertThat(loaded.getRedirectUri()).isEqualTo(original.getRedirectUri());
        assertThat(loaded.getState()).isEqualTo(original.getState());
        assertThat(loaded.getScopes()).isEqualTo(original.getScopes());
    }

    @Test
    @DisplayName("Ciasteczko żądania autoryzacyjnego ma atrybuty HttpOnly, SameSite=Lax i Secure z konfiguracji")
    void savedCookie_shouldHaveExpectedAttributes() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        repository.saveAuthorizationRequest(sampleRequest(), new MockHttpServletRequest(), response);

        String header = response.getHeader("Set-Cookie");

        assertThat(header).contains("HttpOnly");
        assertThat(header).contains("SameSite=Lax");
        assertThat(header).contains("Secure");
        assertThat(header).contains("Max-Age=180");
    }

    @Test
    @DisplayName("removeAuthorizationRequest zwraca zapisane żądanie i kasuje ciasteczko")
    void remove_shouldReturnRequestAndClearCookie() {
        OAuth2AuthorizationRequest original = sampleRequest();

        MockHttpServletResponse saveResponse = new MockHttpServletResponse();
        repository.saveAuthorizationRequest(original, new MockHttpServletRequest(), saveResponse);

        MockHttpServletRequest removeRequest = requestWithCookieFrom(saveResponse);
        MockHttpServletResponse removeResponse = new MockHttpServletResponse();

        OAuth2AuthorizationRequest removed = repository.removeAuthorizationRequest(removeRequest, removeResponse);

        assertThat(removed).isNotNull();
        assertThat(removed.getState()).isEqualTo(original.getState());
        // Max-Age=0 to standardowy sposób na skasowanie ciasteczka w przeglądarce.
        assertThat(removeResponse.getHeader("Set-Cookie")).contains("Max-Age=0");
    }

    @Test
    @DisplayName("Brak ciasteczka daje null, a nie wyjątek")
    void loadWithoutCookie_shouldReturnNull() {
        OAuth2AuthorizationRequest loaded = repository.loadAuthorizationRequest(new MockHttpServletRequest());

        assertThat(loaded).isNull();
    }

    @Test
    @DisplayName("Uszkodzona wartość ciasteczka (zły Base64) daje null, a nie wyjątek")
    void loadWithGarbageCookie_shouldReturnNullNotThrow() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(CookieOAuth2AuthorizationRequestRepository.COOKIE_NAME, "!!!nie-jest-to-base64!!!"));

        OAuth2AuthorizationRequest loaded = repository.loadAuthorizationRequest(request);

        assertThat(loaded).isNull();
    }

    @Test
    @DisplayName("Poprawny Base64, ale nie-JSON, daje null, a nie wyjątek")
    void loadWithInvalidJsonCookie_shouldReturnNullNotThrow() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        // "to nie jest json" zakodowane w Base64 URL - poprawny Base64, złamany JSON.
        String encoded = java.util.Base64.getUrlEncoder()
                .encodeToString("to nie jest json".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        request.setCookies(new Cookie(CookieOAuth2AuthorizationRequestRepository.COOKIE_NAME, encoded));

        OAuth2AuthorizationRequest loaded = repository.loadAuthorizationRequest(request);

        assertThat(loaded).isNull();
    }

    private OAuth2AuthorizationRequest sampleRequest() {
        return OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
                .clientId("test-client-id")
                .redirectUri("https://example.com/login/oauth2/code/google")
                .state("xk3F9pQz")
                .scopes(java.util.Set.of("openid", "email", "profile"))
                .build();
    }

    /**
     * Wyciąga ciasteczko z odpowiedzi zapisanej przez repozytorium i wsadza je
     * do nowego, "przychodzącego" żądania - dokładnie to, co robi przeglądarka
     * między krokiem 1 (przekierowanie do Google) a krokiem 2 (powrót od Google).
     */
    private MockHttpServletRequest requestWithCookieFrom(MockHttpServletResponse response) {
        Cookie savedCookie = response.getCookie(CookieOAuth2AuthorizationRequestRepository.COOKIE_NAME);
        assertThat(savedCookie).as("ciasteczko powinno zostać zapisane").isNotNull();

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(savedCookie.getName(), savedCookie.getValue()));
        return request;
    }
}
