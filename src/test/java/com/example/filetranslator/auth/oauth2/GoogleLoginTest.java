package com.example.filetranslator.auth.oauth2;

import com.example.filetranslator.user.UserRepository;
import com.example.filetranslator.user.model.Role;
import com.example.filetranslator.user.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Logowanie kontem Google - zakładanie konta, łączenie z istniejącym i obie odmowy.
 *
 * CZEGO TU NIE MA I DLACZEGO: pełnej wymiany kodu autoryzacyjnego z Google. Wymagałaby
 * postawienia atrapy serwera autoryzacyjnego, a test przechodziłby wtedy przez atrapę,
 * nie przez Google - sprawdzałby więc bibliotekę Springa, a nie nasz kod. Rozgałęzienia,
 * które są NASZE, siedzą w GoogleOidcUserService.resolve i w handlerze sukcesu i to one
 * są tu wykonywane, na syntetycznym OidcUser.
 *
 * Klasa świadomie NIE używa @TestPropertySource: atrapowy klient OAuth2 stoi
 * w application-test.yml, więc kontekst jest ten sam co dla reszty suite. Osobny
 * kontekst kosztowałby własną pulę połączeń, a próg max_connections PostgreSQL-a
 * został tu już raz przekroczony przez dołożenie jednej klasy testowej.
 */
@SpringBootTest
@ActiveProfiles("test")
class GoogleLoginTest {

    private static final String SUB = "115551234567890123456";
    private static final String EMAIL = "google.user@example.com";

    @Autowired
    private GoogleOidcUserService oidcUserService;

    @Autowired
    private GoogleOAuth2SuccessHandler successHandler;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() {
        userRepository.findByEmail(EMAIL).ifPresent(userRepository::delete);
        userRepository.findByGoogleSub(SUB).ifPresent(userRepository::delete);
    }

    /* ---------------------------------------------------------------------------------
     * Zakładanie i łączenie kont
     * --------------------------------------------------------------------------------- */

    @Test
    @DisplayName("Nowy adres z Google zakłada konto od razu włączone, z zapisanym sub")
    void newEmail_shouldCreateEnabledAccount() {
        User user = oidcUserService.resolve(oidcUser(SUB, EMAIL, true, "Jan Kowalski"));

        assertThat(user.getEmail()).isEqualTo(EMAIL);
        assertThat(user.getGoogleSub()).isEqualTo(SUB);
        assertThat(user.getName()).isEqualTo("Jan Kowalski");
        assertThat(user.getRole()).isEqualTo(Role.USER);
        // enabled = true bez maila potwierdzającego: adres potwierdziło już Google.
        assertThat(user.isEnabled()).isTrue();
    }

    /**
     * SEDNO ZATWIERDZONEJ DECYZJI O ŁĄCZENIU KONT.
     *
     * Konto założone hasłem i logowanie Google na ten sam POTWIERDZONY adres to ten sam
     * człowiek: Google z email_verified = true dowodzi kontroli nad tą samą skrzynką, co
     * kliknięcie w link potwierdzający przy rejestracji. Powstanie drugiego wiersza byłoby
     * dziurą w modelu tożsamości - dwa konta na jeden adres, przy unikacie uk_users_email
     * zresztą niemożliwe do zapisania.
     */
    @Test
    @DisplayName("Adres z istniejącym kontem hasłowym łączy się z nim, nie tworzy drugiego")
    void existingPasswordAccount_shouldBeLinkedNotDuplicated() {
        User existing = createPasswordAccount(EMAIL, "Stara Nazwa");

        User resolved = oidcUserService.resolve(oidcUser(SUB, EMAIL, true, "Nazwa Z Google"));

        assertThat(resolved.getId()).isEqualTo(existing.getId());
        assertThat(resolved.getGoogleSub()).isEqualTo(SUB);
        assertThat(userRepository.findAll().stream()
                .filter(u -> EMAIL.equals(u.getEmail()))
                .count()).isEqualTo(1);
        // Nazwy z Google NIE nadpisujemy - konto jest już czyjeś, a użytkownik mógł
        // zmienić ją u nas. Logowanie nie jest miejscem na cichą edycję cudzych danych.
        assertThat(resolved.getName()).isEqualTo("Stara Nazwa");
    }

    /**
     * Adres z Google przychodzi bez normalizacji, bo nie idzie przez DTO. PostgreSQL
     * porównuje teksty z rozróżnianiem wielkości liter, więc bez jawnego wywołania
     * EmailNormalizer powstałoby DRUGIE konto na ten sam adres pisany inaczej.
     */
    @Test
    @DisplayName("Adres z Google pisany wielkimi literami trafia w istniejące konto")
    void mixedCaseEmail_shouldMatchExistingAccount() {
        User existing = createPasswordAccount(EMAIL, "Jan");

        User resolved = oidcUserService.resolve(oidcUser(SUB, "Google.User@Example.COM", true, "Jan"));

        assertThat(resolved.getId()).isEqualTo(existing.getId());
    }

    /**
     * users.name to VARCHAR(50) NOT NULL, a Google żadnego takiego limitu nie ma.
     * Bez przycięcia dłuższa nazwa daje DataIntegrityViolationException, czyli 500
     * W ŚRODKU PRZEKIEROWANIA Z GOOGLE - tam, gdzie użytkownik nie ma nawet czego ponowić,
     * bo kod autoryzacyjny jest już zużyty.
     */
    @Test
    @DisplayName("Nazwa dłuższa niż kolumna zostaje przycięta, a konto powstaje")
    void overlongName_shouldBeTruncated() {
        String tooLong = "a".repeat(120);

        User user = oidcUserService.resolve(oidcUser(SUB, EMAIL, true, tooLong));

        assertThat(user.getName()).hasSize(50);
    }

    @Test
    @DisplayName("Brak nazwy w tokenie zastępuje część adresu przed małpą")
    void missingName_shouldFallBackToLocalPart() {
        User user = oidcUserService.resolve(oidcUser(SUB, EMAIL, true, null));

        assertThat(user.getName()).isEqualTo("google.user");
    }

    /* ---------------------------------------------------------------------------------
     * Odmowy
     * --------------------------------------------------------------------------------- */

    /**
     * Bez tej kontroli konto Google założone na CUDZY adres przejmowałoby cudze konto -
     * potwierdzony adres jest całym dowodem tożsamości w teście o łączeniu kont wyżej.
     */
    @Test
    @DisplayName("Niepotwierdzony adres jest odrzucany i nie zakłada konta")
    void unverifiedEmail_shouldBeRejected() {
        assertThatThrownBy(() -> oidcUserService.resolve(oidcUser(SUB, EMAIL, false, "Jan")))
                .isInstanceOf(OAuth2AuthenticationException.class)
                .satisfies(e -> assertThat(((OAuth2AuthenticationException) e).getError().getErrorCode())
                        .isEqualTo("GOOGLE_EMAIL_NOT_VERIFIED"));

        assertThat(userRepository.findByEmail(EMAIL)).isEmpty();
    }

    /**
     * KONTROLA POZYTYWNA blokady administracyjnej.
     *
     * Logowanie przez OAuth2 omija w całości DaoAuthenticationProvider, a więc
     * i BlockedAccountChecker. Bez jawnego sprawdzenia w GoogleOidcUserService blokada
     * po prostu NIE DZIAŁA na tej ścieżce: zablokowany dostaje 423 przy logowaniu hasłem
     * i wchodzi bokiem przez Google.
     */
    @Test
    @DisplayName("Konto zablokowane przez administratora nie zaloguje się przez Google")
    void blockedAccount_shouldBeRejected() {
        User user = createPasswordAccount(EMAIL, "Jan");
        user.setBlockedAt(Instant.now());
        user.setBlockedReason("Nadużycia");
        userRepository.save(user);

        assertThatThrownBy(() -> oidcUserService.resolve(oidcUser(SUB, EMAIL, true, "Jan")))
                .isInstanceOf(OAuth2AuthenticationException.class)
                .satisfies(e -> assertThat(((OAuth2AuthenticationException) e).getError().getErrorCode())
                        .isEqualTo("ACCOUNT_BLOCKED"));
    }

    /**
     * KONTROLA NEGATYWNA - i to jest ważniejszy z tej pary testów.
     *
     * Blokada po nieudanych logowaniach (locked_until) NIE MOŻE zamykać logowania przez
     * Google, bo wywołać ją może KAŻDY na KAŻDYM, wpisując kilka razy złe hasło na znany
     * adres. Sprawdzanie tu isAccountNonLocked() zamiast isBlocked() dałoby gotowe
     * narzędzie do odcinania dowolnego użytkownika od logowania Google, bez znajomości
     * jego hasła - a użytkownik logujący się przez Google hasła może w ogóle nie mieć.
     */
    @Test
    @DisplayName("Blokada po nieudanych logowaniach NIE zamyka logowania przez Google")
    void failedLoginLockout_shouldNotBlockGoogleLogin() {
        User user = createPasswordAccount(EMAIL, "Jan");
        user.setFailedLoginAttempts(5);
        user.setLockedUntil(Instant.now().plusSeconds(900));
        userRepository.save(user);

        User resolved = oidcUserService.resolve(oidcUser(SUB, EMAIL, true, "Jan"));

        assertThat(resolved.getId()).isEqualTo(user.getId());
    }

    /* ---------------------------------------------------------------------------------
     * Wystawienie sesji
     * --------------------------------------------------------------------------------- */

    /**
     * Handler sukcesu ma wystawić DOKŁADNIE te same ciasteczka co POST /auth/login -
     * to jest cała podstawa, na której rotacja tokenów, wylogowanie i /auth/refresh
     * działają dla konta Google bez ani jednej linijki napisanej osobno.
     */
    @Test
    @DisplayName("Udane logowanie wystawia oba ciasteczka i przekierowuje na front")
    void successHandler_shouldIssueCookiesAndRedirect() throws Exception {
        OidcUser principal = oidcUser(SUB, EMAIL, true, "Jan");
        oidcUserService.resolve(principal);

        MockHttpServletResponse response = new MockHttpServletResponse();
        successHandler.onAuthenticationSuccess(new MockHttpServletRequest(), response,
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));

        assertThat(response.getHeaders("Set-Cookie"))
                .anySatisfy(header -> assertThat(header).startsWith("accessToken="))
                .anySatisfy(header -> assertThat(header).startsWith("refreshToken="));
        assertThat(response.getRedirectedUrl()).startsWith("http://localhost:5173");
    }

    /* ---------------------------------------------------------------------------------
     * Pomocnicze
     * --------------------------------------------------------------------------------- */

    private User createPasswordAccount(String email, String name) {
        User user = new User();
        user.setEmail(email);
        user.setName(name);
        user.setPassword(passwordEncoder.encode("Haslo123"));
        user.setRole(Role.USER);
        user.setEnabled(true);
        return userRepository.save(user);
    }

    /**
     * Syntetyczny OidcUser - dokładnie taki, jaki zbudowałby Spring z tokenu ID od Google.
     * Nazwa pominięta w mapie roszczeń, gdy null, bo mapa roszczeń nie przyjmuje nulli
     * (i Google przy braku zgody na zakres profile faktycznie tego roszczenia nie przysyła).
     */
    private OidcUser oidcUser(String sub, String email, boolean emailVerified, String name) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("sub", sub);
        claims.put("email", email);
        claims.put("email_verified", emailVerified);
        if (name != null) {
            claims.put("name", name);
        }

        OidcIdToken idToken = new OidcIdToken("wartosc-tokenu",
                Instant.now(), Instant.now().plusSeconds(300), claims);

        return new DefaultOidcUser(List.of(new SimpleGrantedAuthority("OIDC_USER")), idToken);
    }
}
