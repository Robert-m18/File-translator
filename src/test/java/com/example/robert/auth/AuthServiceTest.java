package com.example.robert.auth;

import com.example.robert.auth.model.PasswordResetToken;
import com.example.robert.auth.model.PendingRegistration;
import com.example.robert.auth.repository.PasswordResetTokenRepository;
import com.example.robert.auth.repository.PendingRegistrationRepository;
import com.example.robert.common.exception.InvalidTokenException;
import com.example.robert.common.exception.JwtAuthenticationException;
import com.example.robert.common.exception.TokenExpiredException;
import com.example.robert.common.security.JwtUtil;
import com.example.robert.user.UserService;
import com.example.robert.user.dto.UserRequestDTO;
import com.example.robert.user.model.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.example.robert.notification.MailOutbox;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private AuthenticationManager authenticationManager;

    @Mock
    private JwtUtil jwtUtil;

    @Mock
    private UserService userService;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private PendingRegistrationRepository pendingRegistrationRepository;

    @Mock
    private PasswordResetTokenRepository passwordResetTokenRepository;

    @Mock
    private RefreshTokenService refreshTokenService;

    @Mock
    private MailOutbox mailOutbox;

    @InjectMocks
    private AuthService authService;

    private static final UserRequestDTO DTO =
            new UserRequestDTO("Adrian", "adrian@test.pl", "haslo123");

    private User user(Long id, String email) {
        User user = new User();
        user.setId(id);
        user.setEmail(email);
        user.setName("Adrian");
        user.setEnabled(true);
        return user;
    }

    // ---------- rejestracja ----------

    @Test
    void register_shouldStorePendingRegistration_andNotCreateUser() {
        when(userService.existsByEmail(DTO.email())).thenReturn(false);
        when(passwordEncoder.encode(DTO.password())).thenReturn("zahaszowane");

        authService.register(DTO);

        // Wiersz w users NIE powstaje - konto rodzi się dopiero przy potwierdzeniu adresu
        verify(userService, never()).createConfirmedUser(any(), any(), any());

        ArgumentCaptor<PendingRegistration> saved = ArgumentCaptor.forClass(PendingRegistration.class);
        verify(pendingRegistrationRepository).save(saved.capture());
        assertEquals(DTO.email(), saved.getValue().getEmail());
        // W poczekalni leży hash, nigdy hasło jawne
        assertEquals("zahaszowane", saved.getValue().getPasswordHash());

        // Zamówienie maila w tej samej transakcji - nie wysyłka
        verify(mailOutbox).enqueueVerification(eq(DTO.email()), eq(DTO.name()), anyString());
    }

    @Test
    void register_shouldNotOverwriteOtherAttempts_forSameEmail() {
        /*
         * Sedno ochrony przed przejęciem konta: drugie zgłoszenie na ten sam adres to NOWY
         * wiersz, a nie nadpisanie poprzedniego. Gdyby zgłoszenia się nadpisywały, obca
         * osoba podmieniłaby hash hasła w trwającej rejestracji, a ofiara aktywowałaby
         * konto z cudzym hasłem.
         */
        when(userService.existsByEmail(DTO.email())).thenReturn(false);
        when(passwordEncoder.encode(any())).thenReturn("hash-1", "hash-2");

        authService.register(DTO);
        authService.register(new UserRequestDTO("Napastnik", DTO.email(), "innehaslo9"));

        // Dwa zapisy, żadnego kasowania - oba zgłoszenia żyją obok siebie
        verify(pendingRegistrationRepository, times(2)).save(any(PendingRegistration.class));
        verify(pendingRegistrationRepository, never()).deleteAllByEmail(any());
    }

    @Test
    void register_shouldStaySilentAndSkipPending_whenAccountAlreadyExists() {
        when(userService.existsByEmail(DTO.email())).thenReturn(true);

        // Brak wyjątku - inaczej endpoint zdradzałby, że adres jest zarejestrowany
        authService.register(DTO);

        verify(pendingRegistrationRepository, never()).save(any());
        // Właściciel skrzynki dostaje powiadomienie, a nie link do potwierdzenia -
        // nie ma czego potwierdzać, bo konto już istnieje
        verify(mailOutbox).enqueueAccountExists(DTO.email());
        verify(mailOutbox, never()).enqueueVerification(any(), any(), any());
    }

    // ---------- potwierdzenie ----------

    @Test
    void confirmEmail_shouldCreateUserFromPendingRegistration() {
        PendingRegistration pending = new PendingRegistration(
                "adrian@test.pl", "Adrian", "hash-z-poczekalni", "token-hash",
                Instant.now(), Instant.now().plus(Duration.ofHours(24)));

        when(pendingRegistrationRepository.findByTokenHash(any())).thenReturn(Optional.of(pending));
        when(userService.existsByEmail("adrian@test.pl")).thenReturn(false);
        when(userService.createConfirmedUser(any(), any(), any())).thenReturn(user(1L, "adrian@test.pl"));

        authService.confirmEmail("surowy-token");

        // Hash przenoszony bez ponownego kodowania - inaczej powstałby hash hasha
        verify(userService).createConfirmedUser("adrian@test.pl", "Adrian", "hash-z-poczekalni");
        verify(passwordEncoder, never()).encode(any());
        // Pozostałe zgłoszenia na ten adres (także obce) tracą sens
        verify(pendingRegistrationRepository).deleteAllByEmail("adrian@test.pl");
    }

    @Test
    void confirmEmail_shouldNotTouchExistingAccount_whenUserAlreadyCreated() {
        // Drugi link z dwóch zgłoszeń tej samej osoby. Nadpisanie hasła istniejącego
        // konta danymi ze starszego zgłoszenia byłoby cichą zmianą hasła.
        PendingRegistration pending = new PendingRegistration(
                "adrian@test.pl", "Adrian", "stary-hash", "token-hash",
                Instant.now(), Instant.now().plus(Duration.ofHours(24)));

        when(pendingRegistrationRepository.findByTokenHash(any())).thenReturn(Optional.of(pending));
        when(userService.existsByEmail("adrian@test.pl")).thenReturn(true);

        authService.confirmEmail("surowy-token");

        verify(userService, never()).createConfirmedUser(any(), any(), any());
        verify(userService, never()).updatePassword(any(), any());
        verify(pendingRegistrationRepository).deleteAllByEmail("adrian@test.pl");
    }

    @Test
    void confirmEmail_shouldRejectUnknownToken() {
        when(pendingRegistrationRepository.findByTokenHash(any())).thenReturn(Optional.empty());

        assertThrows(InvalidTokenException.class, () -> authService.confirmEmail("nieznany"));
        verify(userService, never()).createConfirmedUser(any(), any(), any());
    }

    @Test
    void confirmEmail_shouldRejectExpiredToken() {
        PendingRegistration expired = new PendingRegistration(
                "adrian@test.pl", "Adrian", "hash", "token-hash",
                Instant.now().minus(Duration.ofHours(48)), Instant.now().minus(Duration.ofHours(24)));

        when(pendingRegistrationRepository.findByTokenHash(any())).thenReturn(Optional.of(expired));

        assertThrows(TokenExpiredException.class, () -> authService.confirmEmail("wygasly"));
        verify(userService, never()).createConfirmedUser(any(), any(), any());
    }

    // ---------- reset hasła ----------

    @Test
    void requestPasswordReset_shouldInvalidatePreviousTokens_andIssueNew() {
        when(userService.findEntityByEmail("adrian@test.pl")).thenReturn(Optional.of(user(1L, "adrian@test.pl")));

        authService.requestPasswordReset("adrian@test.pl");

        // W obiegu ma być najwyżej jeden żywy link - każdy jest pełnym kluczem do konta
        verify(passwordResetTokenRepository).invalidateAllForUser(eq(1L), any());
        verify(passwordResetTokenRepository).save(any(PasswordResetToken.class));
        verify(mailOutbox).enqueuePasswordReset(eq("adrian@test.pl"), any(), anyString());
    }

    @Test
    void requestPasswordReset_shouldStaySilent_forUnknownEmail() {
        when(userService.findEntityByEmail("nieznany@test.pl")).thenReturn(Optional.empty());

        authService.requestPasswordReset("nieznany@test.pl");

        // Żadnego maila i żadnego zapisu - inaczej czas odpowiedzi i skutki uboczne
        // zdradzałyby, które adresy istnieją
        verify(passwordResetTokenRepository, never()).save(any());
        verifyNoInteractions(mailOutbox);
    }

    @Test
    void resetPassword_shouldSetNewPassword_revokeSessions_andClearLock() {
        User target = user(7L, "adrian@test.pl");
        PasswordResetToken token = new PasswordResetToken(
                "token-hash", target, Instant.now(), Instant.now().plus(Duration.ofHours(1)));

        when(passwordResetTokenRepository.findByTokenHash(any())).thenReturn(Optional.of(token));
        when(passwordEncoder.encode("NoweHaslo9")).thenReturn("nowy-hash");

        authService.resetPassword("surowy-token", "NoweHaslo9");

        verify(userService).updatePassword(target, "nowy-hash");
        // Bez tego napastnik z ważnym tokenem odświeżającym zostaje w koncie na tydzień
        verify(refreshTokenService).revokeAllSessions(7L);
        verify(userService).clearLoginFailures("adrian@test.pl");
        // Token jednorazowy - drugie kliknięcie tego samego linku musi odpaść
        org.junit.jupiter.api.Assertions.assertNotNull(token.getUsedAt());
    }

    @Test
    void resetPassword_shouldRejectAlreadyUsedToken() {
        User target = user(7L, "adrian@test.pl");
        PasswordResetToken used = new PasswordResetToken(
                "token-hash", target, Instant.now(), Instant.now().plus(Duration.ofHours(1)));
        used.setUsedAt(Instant.now());

        when(passwordResetTokenRepository.findByTokenHash(any())).thenReturn(Optional.of(used));

        assertThrows(InvalidTokenException.class,
                () -> authService.resetPassword("surowy-token", "NoweHaslo9"));
        verify(userService, never()).updatePassword(any(), any());
        verifyNoInteractions(refreshTokenService);
    }

    @Test
    void resetPassword_shouldRejectExpiredToken() {
        User target = user(7L, "adrian@test.pl");
        PasswordResetToken expired = new PasswordResetToken(
                "token-hash", target, Instant.now().minus(Duration.ofHours(2)), Instant.now().minus(Duration.ofHours(1)));

        when(passwordResetTokenRepository.findByTokenHash(any())).thenReturn(Optional.of(expired));

        assertThrows(TokenExpiredException.class,
                () -> authService.resetPassword("surowy-token", "NoweHaslo9"));
        verify(userService, never()).updatePassword(any(), any());
    }

    // ---------- logowanie i sesje ----------

    @Test
    void login_shouldReturnTokenPair() {
        when(authenticationManager.authenticate(any()))
                .thenReturn(new UsernamePasswordAuthenticationToken(DTO.email(), DTO.password()));
        when(jwtUtil.generateToken(any())).thenReturn("mocked-jwt-token");

        var result = authService.login(DTO.email(), DTO.password());

        assertEquals("mocked-jwt-token", result.accessToken());
        // Logowanie musi zarejestrować sesję w bazie - bez tego nie da się jej później
        // unieważnić przy wylogowaniu ani wykryć kradzieży tokenu.
        verify(refreshTokenService, times(1)).startSession(eq(DTO.email()), any());
    }

    @Test
    void login_shouldThrowException_whenCredentialsInvalid() {
        when(authenticationManager.authenticate(any()))
                .thenThrow(new BadCredentialsException("Złe dane logowania"));

        assertThrows(BadCredentialsException.class,
                () -> authService.login("adrian@test.pl", "złehaslo"));
    }

    @Test
    void refreshToken_shouldReturnNewTokenPair() {
        String refreshToken = "mocked-refresh-token";
        when(jwtUtil.extractTokenType(refreshToken)).thenReturn("refresh");
        when(jwtUtil.generateToken(any())).thenReturn("new-access-token");
        when(jwtUtil.generateRefreshToken(any())).thenReturn("new-refresh-token");

        var result = authService.refreshToken(refreshToken);

        assertEquals("new-access-token", result.accessToken());
        assertEquals("new-refresh-token", result.refreshToken());
        // Rotacja: stary token musi zostać zużyty, a nowy zapisany w tej samej rodzinie
        verify(refreshTokenService, times(1)).rotate(refreshToken, "new-refresh-token");
    }

    @Test
    void refreshToken_shouldNotRotate_whenTokenTypeIsWrong() {
        // given - podstawiony access token zamiast odświeżającego
        when(jwtUtil.extractTokenType("podstawiony-token")).thenReturn("access");

        assertThrows(JwtAuthenticationException.class, () -> authService.refreshToken("podstawiony-token"));
        // Baza nie może zostać w ogóle dotknięta - odsiewamy to na poziomie kryptografii
        verifyNoInteractions(refreshTokenService);
    }

    @Test
    void refreshToken_shouldReportInvalidTokenTypeCode() {
        String refreshToken = "mocked-refresh-token";
        when(jwtUtil.extractTokenType(refreshToken)).thenReturn("access");

        String code = assertThrows(JwtAuthenticationException.class,
                () -> authService.refreshToken(refreshToken)).getTokenError();

        verify(jwtUtil, never()).generateToken(any());
        verify(jwtUtil, never()).generateRefreshToken(any());
        assertEquals("INVALID_TOKEN_TYPE", code);
    }

    @Test
    void logout_shouldRevokeSession() {
        authService.logout("refresh-token-do-uniewaznienia");

        verify(refreshTokenService, times(1)).revokeSession("refresh-token-do-uniewaznienia");
    }

    @Test
    void logout_shouldBeNoOp_whenNoRefreshTokenPresent() {
        // Wylogowanie bez ciasteczka nie może się wywalić - kontroler i tak czyści ciasteczka
        authService.logout(null);

        verifyNoInteractions(refreshTokenService);
    }
}
