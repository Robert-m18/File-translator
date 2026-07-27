/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.robert.auth;


import com.example.robert.auth.dto.ConfirmEmailRequest;
import com.example.robert.auth.dto.CsrfTokenResponse;
import com.example.robert.auth.dto.ForgotPasswordRequest;
import com.example.robert.auth.dto.LoginRequest;
import com.example.robert.auth.dto.ResetPasswordRequest;
import com.example.robert.auth.dto.TokenPair;
import com.example.robert.user.dto.UserRequestDTO;
import com.example.robert.common.exception.JwtAuthenticationException;
import com.example.robert.common.web.SuccessMessage;
import com.example.robert.auth.AuthService;
import com.example.robert.auth.CookieService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDateTime;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final CookieService cookieService;




    @PostMapping("/login")
    public ResponseEntity<SuccessMessage> login(@Valid @RequestBody LoginRequest request,
                                                HttpServletResponse response) {
        TokenPair tokens = authService.login(request.email(), request.password());

        response.addHeader(HttpHeaders.SET_COOKIE,
                cookieService.createAccessTokenCookie(tokens.accessToken()).toString());
        response.addHeader(HttpHeaders.SET_COOKIE,
                cookieService.createRefreshTokenCookie(tokens.refreshToken()).toString());


        return ResponseEntity.status(HttpStatus.OK)
                .body(new SuccessMessage("Zalogowano pomyślnie", LocalDateTime.now()));
    }

    /**
     * 202, nie 201: w tym momencie nic jeszcze nie powstało. Zgłoszenie leży
     * w pending_registrations i zamieni się w konto dopiero po potwierdzeniu adresu.
     *
     * Odpowiedź jest IDENTYCZNA dla adresu wolnego i już zarejestrowanego. Nie ma tu
     * ścieżki zwracającej 409 - byłaby to wyrocznia pozwalająca odpytać, kto ma u nas
     * konto. Właściciel istniejącego konta dowiaduje się o próbie mailem.
     */
    @PostMapping("/register")
    public ResponseEntity<SuccessMessage> register(
            @Valid @RequestBody UserRequestDTO request) {

        authService.register(request);

        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new SuccessMessage("Sprawdź swoją skrzynkę email, aby dokończyć rejestrację", LocalDateTime.now()));
    }

    @PostMapping("/confirm")
    public ResponseEntity<SuccessMessage> confirmEmail(@Valid @RequestBody ConfirmEmailRequest request) {
        authService.confirmEmail(request.token());

        return ResponseEntity.ok(new SuccessMessage("Email potwierdzony, możesz się zalogować", LocalDateTime.now()));
    }

    /**
     * Ten sam komunikat dla adresu znanego i nieznanego - endpoint resetu hasła jest
     * klasycznym miejscem wycieku listy użytkowników.
     */
    @PostMapping("/forgot-password")
    public ResponseEntity<SuccessMessage> forgotPassword(
            @Valid @RequestBody ForgotPasswordRequest request) {

        authService.requestPasswordReset(request.email());

        return ResponseEntity.ok(new SuccessMessage(
                "Jeśli konto istnieje, link do ustawienia nowego hasła został wysłany",
                LocalDateTime.now()));
    }

    @PostMapping("/reset-password")
    public ResponseEntity<SuccessMessage> resetPassword(
            @Valid @RequestBody ResetPasswordRequest request,
            HttpServletResponse response) {

        authService.resetPassword(request.token(), request.password());

        // Ciasteczka czyścimy, bo reset unieważnił wszystkie sesje po stronie serwera.
        // Zostawienie ich oznaczałoby, że przeglądarka dalej wysyła martwe tokeny
        // i użytkownik dostaje 401 zamiast czystego ekranu logowania.
        response.addHeader(HttpHeaders.SET_COOKIE, cookieService.clearAccessTokenCookie().toString());
        response.addHeader(HttpHeaders.SET_COOKIE, cookieService.clearRefreshTokenCookie().toString());

        return ResponseEntity.ok(new SuccessMessage(
                "Hasło zmienione. Zaloguj się ponownie na wszystkich urządzeniach.",
                LocalDateTime.now()));
    }

    /**
     * Wydaje token CSRF frontendowi. Wołane raz, przy starcie aplikacji klienckiej,
     * przed pierwszym żądaniem zmieniającym stan. Szczegóły: CsrfTokenResponse.
     */
    @GetMapping("/csrf")
    public CsrfTokenResponse csrfToken(CsrfToken token) {
        return new CsrfTokenResponse(token.getHeaderName(), token.getToken());
    }

    @PostMapping("/refresh")
    public ResponseEntity<SuccessMessage> refresh(
            @CookieValue(name = "refreshToken", required = false) String refreshToken,
            HttpServletResponse response) {

        if (refreshToken == null) {
            // Rzucamy wyjątek zamiast budować pustą odpowiedź 401: GlobalExceptionHandler
            // zamieni go na ProblemDetail z polem "code", tak samo jak każdy inny błąd API.
            // Wcześniej był to jedyny endpoint zwracający puste ciało - front wywalał się
            // na response.json() zamiast dostać czytelny powód.
            throw new JwtAuthenticationException(
                    "Brak tokenu odświeżającego - zaloguj się ponownie", "REFRESH_TOKEN_MISSING");
        }

        TokenPair newTokens = authService.refreshToken(refreshToken);

        response.addHeader(HttpHeaders.SET_COOKIE,
                cookieService.createAccessTokenCookie(newTokens.accessToken()).toString());
        response.addHeader(HttpHeaders.SET_COOKIE,
                cookieService.createRefreshTokenCookie(newTokens.refreshToken()).toString());

        return ResponseEntity.ok(new SuccessMessage("Token odświeżony", LocalDateTime.now()));
    }

    @PostMapping("/logout")
    public ResponseEntity<SuccessMessage> logout(
            @CookieValue(name = "refreshToken", required = false) String refreshToken,
            HttpServletResponse response) {

        // Unieważnia sesję po stronie serwera. Samo skasowanie ciasteczek nie wystarcza:
        // kopia tokenu przechwycona wcześniej działałaby dalej aż do wygaśnięcia.
        authService.logout(refreshToken);

        response.addHeader(HttpHeaders.SET_COOKIE,
                cookieService.clearAccessTokenCookie().toString());
        response.addHeader(HttpHeaders.SET_COOKIE,
                cookieService.clearRefreshTokenCookie().toString());

        return ResponseEntity.ok(new SuccessMessage("Wylogowano pomyślnie", LocalDateTime.now()));
    }

}