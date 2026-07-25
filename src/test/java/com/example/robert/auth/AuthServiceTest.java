package com.example.robert.auth;

import com.example.robert.user.UserService;
import com.example.robert.user.dto.UserRequestDTO;
import com.example.robert.common.security.JwtUtil;
import com.example.robert.auth.UserRegisteredEvent;
import com.example.robert.common.exception.EmailAlreadyExistException;
import com.example.robert.common.exception.JwtAuthenticationException;
import com.example.robert.user.model.User;
import com.example.robert.auth.repository.VerificationTokenRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

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
    private VerificationTokenRepository verificationTokenRepository;

    @Mock
    private RefreshTokenService refreshTokenService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private AuthService authService;


    @Test
    void register_shouldSaveUser_whenEmailNotExists() {
        // given
        UserRequestDTO dto = new UserRequestDTO("Adrian", "adrian@test.pl", "haslo123");
        User mockUser = new User();
        mockUser.setId(1L);
        mockUser.setEmail(dto.email());
        mockUser.setName(dto.name());
        mockUser.setEnabled(false);

        when(userService.userExistsByEmail(dto.email())).thenReturn(false);
        when(userService.saveUser(dto)).thenReturn(mockUser);

        // when
        authService.register(dto);

        // then
        verify(userService, times(1)).saveUser(dto);
        verify(verificationTokenRepository, times(1)).save(any());
        verify(eventPublisher, times(1)).publishEvent(isA(UserRegisteredEvent.class));
    }

    @Test
    void register_shouldThrowException_whenEmailAlreadyExists() {
        // given
        UserRequestDTO dto = new UserRequestDTO("Adrian", "adrian@test.pl", "haslo123");
        when(userService.userExistsByEmail(dto.email())).thenReturn(true);

        // when / then
        assertThrows(EmailAlreadyExistException.class, () -> authService.register(dto));
         verify(userService, never()).saveUser(any());
    }

    @Test
    void login_shouldReturnTokenPair() {
        // given
        UserRequestDTO dto = new UserRequestDTO("Adrian", "adrian@test.pl", "haslo123");
        when(authenticationManager.authenticate(any())).thenReturn(new UsernamePasswordAuthenticationToken(dto.email(), dto.password()));
        when(jwtUtil.generateToken(any())).thenReturn("mocked-jwt-token");

        // when
        var result = authService.login(dto.email(), dto.password());

        // then
        assertEquals("mocked-jwt-token", result.accessToken());
        // Logowanie musi zarejestrować sesję w bazie - bez tego nie da się jej później
        // unieważnić przy wylogowaniu ani wykryć kradzieży tokenu.
        verify(refreshTokenService, times(1)).startSession(eq(dto.email()), any());
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
        // given
        String refreshToken = "mocked-refresh-token";
        when(jwtUtil.extractTokenType(refreshToken)).thenReturn("refresh");
        when(jwtUtil.generateToken(any())).thenReturn("new-access-token");
        when(jwtUtil.generateRefreshToken(any())).thenReturn("new-refresh-token");
        // when
        var result = authService.refreshToken(refreshToken);
        // then
        assertEquals("new-access-token", result.accessToken());
        assertEquals("new-refresh-token", result.refreshToken());
        // Rotacja: stary token musi zostać zużyty, a nowy zapisany w tej samej rodzinie
        verify(refreshTokenService, times(1)).rotate(refreshToken, "new-refresh-token");
    }

    @Test
    void refreshToken_shouldNotRotate_whenTokenTypeIsWrong() {
        // given - podstawiony access token zamiast odświeżającego
        when(jwtUtil.extractTokenType("podstawiony-token")).thenReturn("access");

        // when / then
        assertThrows(JwtAuthenticationException.class, () -> authService.refreshToken("podstawiony-token"));
        // Baza nie może zostać w ogóle dotknięta - odsiewamy to na poziomie kryptografii
        verifyNoInteractions(refreshTokenService);
    }

    @Test
    void logout_shouldRevokeSession() {
        // when
        authService.logout("refresh-token-do-uniewaznienia");

        // then
        verify(refreshTokenService, times(1)).revokeSession("refresh-token-do-uniewaznienia");
    }

    @Test
    void logout_shouldBeNoOp_whenNoRefreshTokenPresent() {
        // Wylogowanie bez ciasteczka nie może się wywalić - kontroler i tak czyści ciasteczka
        authService.logout(null);

        verifyNoInteractions(refreshTokenService);
    }

    @Test
    void refreshToken_shouldThrowException_whenInvalidTokenType() {
        // given
        String refreshToken = "mocked-refresh-token";
        when(jwtUtil.extractTokenType(refreshToken)).thenReturn("access");
        // when / then
       String result =  assertThrows(JwtAuthenticationException.class, () -> authService.refreshToken(refreshToken)).getTokenError();
        verify(jwtUtil, never()).generateToken(any());
        verify(jwtUtil, never()).generateRefreshToken(any());
        assertEquals("INVALID_TOKEN_TYPE", result);
    }
}
