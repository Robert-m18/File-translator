package com.example.robert.common.security;

import com.example.robert.common.exception.JwtAuthenticationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Prosty unit test - bez @SpringBootTest, bo JwtUtil nie ma zależności
 * od innych beanów. Kontener Springa jest tu zbędny i tylko spowolniłby test.
 *
 * Dzięki constructor injection w JwtUtil nie potrzebujemy ReflectionTestUtils -
 * po prostu tworzymy obiekt przez "new" jak każdy zwykły obiekt Javy.
 */
class JwtUtilTest {

    private JwtUtil jwtUtil;

    // Klucz musi mieć min. 256 bitów dla HS256, inaczej Keys.hmacShaKeyFor rzuci wyjątek
    private static final String TEST_SECRET = "dGVzdFNlY3JldEtleUZvckpXVFRlc3RpbmdQdXJwb3Nlc09ubHkxMjM0NTY=";

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil(TEST_SECRET, 3_600_000L, 604_800_000L); // secret, 1h, 7 dni
    }

    @Test
    void generateToken_shouldReturnNonEmptyToken() {
        // given / when
        String token = jwtUtil.generateToken("adrian");

        // then
        assertThat(token).isNotBlank();
    }

    @Test
    void extractUsername_shouldReturnUsernameFromGeneratedToken() {
        String token = jwtUtil.generateToken("adrian");

        String username = jwtUtil.extractUsername(token);

        assertThat(username).isEqualTo("adrian");
    }

    @Test
    void extractUsername_shouldThrowExpiredException_whenTokenExpired() {
        // osobna instancja z ujemnym czasem ważności - token wygasły od razu po utworzeniu
        JwtUtil expiredJwtUtil = new JwtUtil(TEST_SECRET, -1000L, 604_800_000L);
        String expiredToken = expiredJwtUtil.generateToken("adrian");

        // Wygaśnięcie wykrywa sam parser, więc sprawdzamy je na tej metodzie,
        // której faktycznie używa JwtFilter - nie na osobnym walidatorze.
        JwtAuthenticationException ex = assertThrows(
                JwtAuthenticationException.class,
                () -> expiredJwtUtil.extractUsername(expiredToken)
        );
        assertThat(ex.getTokenError()).isEqualTo("EXPIRED_TOKEN");
    }

    @Test
    void extractUsername_shouldThrowInvalidTokenException_forGarbageInput() {
        String garbage = "to.nie.jest.token";

        JwtAuthenticationException ex = assertThrows(
                JwtAuthenticationException.class,
                () -> jwtUtil.extractUsername(garbage)
        );
        assertThat(ex.getTokenError()).isEqualTo("INVALID_TOKEN");
    }

    @Test
    void extractTokenType_shouldReturnAccess_forAccessToken() {
        String token = jwtUtil.generateToken("adrian");

        assertThat(jwtUtil.extractTokenType(token)).isEqualTo("access");
    }

    @Test
    void extractTokenType_shouldReturnRefresh_forRefreshToken() {
        String token = jwtUtil.generateRefreshToken("adrian");

        assertThat(jwtUtil.extractTokenType(token)).isEqualTo("refresh");
    }
}