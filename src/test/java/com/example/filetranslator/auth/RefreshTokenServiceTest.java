package com.example.filetranslator.auth;

import com.example.filetranslator.common.security.TokenHasher;
import com.example.filetranslator.common.exception.JwtAuthenticationException;
import com.example.filetranslator.auth.model.RefreshToken;
import com.example.filetranslator.user.model.User;
import com.example.filetranslator.auth.repository.RefreshTokenRepository;
import com.example.filetranslator.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Testy rotacji tokenów odświeżających - w szczególności wykrywania ponownego użycia,
 * które jest jedynym mechanizmem odcinającym skradziony token przed jego wygaśnięciem.
 */
@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {

    private static final long REFRESH_TTL_MS = 604_800_000L; // 7 dni

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private UserRepository userRepository;

    private RefreshTokenService refreshTokenService;

    private User user;

    @BeforeEach
    void setUp() {
        // Menedżer transakcji bez faktycznej bazy - TransactionTemplate ma tylko wykonać
        // przekazaną lambdę, a zachowanie transakcyjne weryfikują testy integracyjne
        // (SessionLifecycleTest).
        PlatformTransactionManager noOpTransactionManager = new AbstractPlatformTransactionManager() {
            @Override protected Object doGetTransaction() { return new Object(); }
            @Override protected void doBegin(Object transaction, TransactionDefinition definition) { }
            @Override protected void doCommit(DefaultTransactionStatus status) { }
            @Override protected void doRollback(DefaultTransactionStatus status) { }
        };

        refreshTokenService = new RefreshTokenService(
                refreshTokenRepository, userRepository, noOpTransactionManager, REFRESH_TTL_MS);

        user = new User();
        user.setId(1L);
        user.setEmail("robert@example.com");
    }

    private RefreshToken activeToken(String rawToken, String familyId) {
        Instant now = Instant.now();
        return new RefreshToken(TokenHasher.sha256Hex(rawToken), user, familyId,
                now.minus(Duration.ofMinutes(5)), now.plus(Duration.ofDays(7)));
    }

    @Test
    @DisplayName("Logowanie zakłada nową rodzinę tokenów")
    void startSession_shouldPersistTokenWithNewFamily() {
        when(userRepository.findByEmail("robert@example.com")).thenReturn(Optional.of(user));

        refreshTokenService.startSession("robert@example.com", "surowy-token");

        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository).save(captor.capture());

        RefreshToken saved = captor.getValue();
        // W bazie ląduje wyłącznie hash - surowy token nie może być odtwarzalny z dumpu
        assertThat(saved.getTokenHash()).isEqualTo(TokenHasher.sha256Hex("surowy-token"));
        assertThat(saved.getTokenHash()).isNotEqualTo("surowy-token");
        assertThat(saved.getFamilyId()).isNotBlank();
        assertThat(saved.getRevokedAt()).isNull();
    }

    @Test
    @DisplayName("Rotacja zużywa stary token i zapisuje nowy w tej samej rodzinie")
    void rotate_shouldRevokeOldAndPersistNewInSameFamily() {
        RefreshToken current = activeToken("stary-token", "rodzina-1");
        when(refreshTokenRepository.findByTokenHash(TokenHasher.sha256Hex("stary-token")))
                .thenReturn(Optional.of(current));

        refreshTokenService.rotate("stary-token", "nowy-token");

        // Stary token przestaje działać - to jest istota rotacji
        assertThat(current.getRevokedAt()).isNotNull();

        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository).save(captor.capture());
        assertThat(captor.getValue().getTokenHash()).isEqualTo(TokenHasher.sha256Hex("nowy-token"));
        // Ta sama rodzina - dzięki temu da się później unieważnić cały łańcuch naraz
        assertThat(captor.getValue().getFamilyId()).isEqualTo("rodzina-1");
    }

    @Test
    @DisplayName("Ponowne użycie zużytego tokenu unieważnia całą rodzinę")
    void rotate_shouldRevokeWholeFamily_whenTokenAlreadyUsed() {
        RefreshToken alreadyUsed = activeToken("skradziony-token", "rodzina-1");
        alreadyUsed.setRevokedAt(Instant.now().minus(Duration.ofMinutes(1)));

        when(refreshTokenRepository.findByTokenHash(TokenHasher.sha256Hex("skradziony-token")))
                .thenReturn(Optional.of(alreadyUsed));

        JwtAuthenticationException ex = assertThrows(JwtAuthenticationException.class,
                () -> refreshTokenService.rotate("skradziony-token", "nowy-token"));

        assertThat(ex.getTokenError()).isEqualTo("REFRESH_TOKEN_REUSED");
        // Cała sesja pada - napastnik i prawowity użytkownik tracą dostęp,
        // bo nie da się rozstrzygnąć, który z nich jest który
        verify(refreshTokenRepository).revokeFamily(eq("rodzina-1"), any(Instant.class));
        // Żaden nowy token nie może zostać wydany
        verify(refreshTokenRepository, never()).save(any());
    }

    @Test
    @DisplayName("Nieznany token odświeżający jest odrzucany")
    void rotate_shouldRejectUnknownToken() {
        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.empty());

        JwtAuthenticationException ex = assertThrows(JwtAuthenticationException.class,
                () -> refreshTokenService.rotate("nieznany-token", "nowy-token"));

        assertThat(ex.getTokenError()).isEqualTo("REFRESH_TOKEN_UNKNOWN");
        verify(refreshTokenRepository, never()).save(any());
    }

    @Test
    @DisplayName("Wygasły token odświeżający jest odrzucany")
    void rotate_shouldRejectExpiredToken() {
        Instant now = Instant.now();
        RefreshToken expired = new RefreshToken(TokenHasher.sha256Hex("wygasly-token"), user, "rodzina-1",
                now.minus(Duration.ofDays(8)), now.minus(Duration.ofDays(1)));
        when(refreshTokenRepository.findByTokenHash(TokenHasher.sha256Hex("wygasly-token")))
                .thenReturn(Optional.of(expired));

        JwtAuthenticationException ex = assertThrows(JwtAuthenticationException.class,
                () -> refreshTokenService.rotate("wygasly-token", "nowy-token"));

        assertThat(ex.getTokenError()).isEqualTo("EXPIRED_TOKEN");
    }

    @Test
    @DisplayName("Wylogowanie unieważnia całą rodzinę, czyli sesję danego urządzenia")
    void revokeSession_shouldRevokeFamily() {
        RefreshToken current = activeToken("token-sesji", "rodzina-7");
        when(refreshTokenRepository.findByTokenHash(TokenHasher.sha256Hex("token-sesji")))
                .thenReturn(Optional.of(current));

        refreshTokenService.revokeSession("token-sesji");

        verify(refreshTokenRepository).revokeFamily(eq("rodzina-7"), any(Instant.class));
    }

    @Test
    @DisplayName("Wylogowanie nieznanym tokenem nie rzuca wyjątku (idempotencja)")
    void revokeSession_shouldBeIdempotent() {
        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.empty());

        refreshTokenService.revokeSession("nieznany-token");

        verify(refreshTokenRepository, never()).revokeFamily(any(), any());
    }
}
