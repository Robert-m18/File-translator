/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.robert.auth;

import com.example.robert.user.UserService;
import com.example.robert.auth.dto.TokenPair;
import com.example.robert.user.dto.UserRequestDTO;
import com.example.robert.common.security.JwtUtil;
import com.example.robert.auth.UserRegisteredEvent;
import com.example.robert.common.exception.EmailAlreadyExistException;
import com.example.robert.common.exception.InvalidTokenException;
import com.example.robert.common.exception.JwtAuthenticationException;
import com.example.robert.common.exception.TokenExpiredException;
import com.example.robert.user.model.User;
import com.example.robert.auth.model.VerificationToken;
import com.example.robert.auth.repository.VerificationTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * Service do obsługi autentykacji - login i rejestracja
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private static final long TOKEN_VALIDITY_HOURS = 24;

    private final AuthenticationManager authenticationManager;
    private final JwtUtil jwtUtil;
    private final UserService userService;
    private final VerificationTokenRepository verificationTokenRepository;
    private final RefreshTokenService refreshTokenService;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public TokenPair login(String email, String password) {
        // Rzuca BadCredentialsException / DisabledException / LockedException -
        // wszystkie mapowane centralnie w GlobalExceptionHandler.
        Authentication auth = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(email, password)
        );

        String accessToken = jwtUtil.generateToken(auth.getName());
        String refreshToken = jwtUtil.generateRefreshToken(auth.getName());

        // Token odświeżający jest rejestrowany w bazie - dopiero to pozwala go później
        // unieważnić (wylogowanie, wykrycie kradzieży). Sam JWT jest nieodwoływalny.
        refreshTokenService.startSession(auth.getName(), refreshToken);

        // Bez adresu email w logu - to dane osobowe, a logi trafiają do systemów,
        // które mają zwykle szerszy dostęp niż sama baza. Do powiązania wpisu
        // z konkretnym żądaniem służy traceId dokładany przez TraceIdFilter.
        log.info("Poprawne logowanie użytkownika");
        return new TokenPair(accessToken, refreshToken);
    }

    @Transactional
    public void register(UserRequestDTO dto) {
        log.info("Próba rejestracji nowego użytkownika");

        if (userService.userExistsByEmail(dto.email())) {
            log.warn("Email już istnieje w bazie");
            throw new EmailAlreadyExistException("User with this email already exists!");
        }

        User savedUser = userService.saveUser(dto); // enabled=false w środku

        String rawToken = UUID.randomUUID().toString();
        String tokenHash = hashToken(rawToken);
        VerificationToken verificationToken = new VerificationToken(
                tokenHash, savedUser, LocalDateTime.now().plusHours(TOKEN_VALIDITY_HOURS)
        );
        verificationTokenRepository.save(verificationToken);

        // Event odpala się DOPIERO po commicie transakcji (patrz UserRegisteredEventListener).
        // Gdyby coś wywaliło wyjątek wyżej i transakcja się wycofała - mail nigdy nie poleci.
        eventPublisher.publishEvent(new UserRegisteredEvent(
                savedUser.getId(), savedUser.getEmail(), savedUser.getName(), rawToken
        ));

        log.info("Użytkownik zarejestrowany (id={}), czeka na potwierdzenie adresu email", savedUser.getId());
    }

    @Transactional
    public void confirmEmail(String rawToken) {
        String hash = hashToken(rawToken);
        VerificationToken token = verificationTokenRepository.findByTokenHash(hash)
                .orElseThrow(() -> new InvalidTokenException("Nieprawidłowy token potwierdzający"));

        if (token.isExpired()) {
            throw new TokenExpiredException("Token wygasł, poproś o nowy link");
        }

        User user = token.getUser();
        user.setEnabled(true);
        // user jest zarządzany przez Hibernate (dirty checking) - nie trzeba wołać save()

        verificationTokenRepository.delete(token);
        log.info("Email potwierdzony dla użytkownika id={}", user.getId());
    }

    private String hashToken(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hashed) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // should never happen - SHA-256 is available
            throw new RuntimeException(e);
        }
    }

    @Transactional
    public TokenPair refreshToken(String refreshToken) {
        // 1. Kryptografia: podpis i typ tokenu. Odsiewa tokeny podrobione i access tokeny
        //    podstawione w miejsce odświeżających, zanim w ogóle ruszymy bazę.
        String type = jwtUtil.extractTokenType(refreshToken);
        if (!"refresh".equals(type)) {
            throw new JwtAuthenticationException("Nieprawidłowy typ tokenu", "INVALID_TOKEN_TYPE");
        }

        String username = jwtUtil.extractUsername(refreshToken);
        String accessToken = jwtUtil.generateToken(username);
        String newRefreshToken = jwtUtil.generateRefreshToken(username);

        // 2. Stan po stronie serwera: czy ten token nie został już zużyty albo unieważniony.
        //    Rotacja zużywa stary token i zapisuje nowy w tej samej rodzinie.
        //    Rzuca wyjątek, jeśli token jest nieznany, zużyty (kradzież) lub wygasły -
        //    wygenerowane wyżej tokeny są wtedy po prostu porzucane.
        refreshTokenService.rotate(refreshToken, newRefreshToken);

        return new TokenPair(accessToken, newRefreshToken);
    }

    @Transactional
    public void logout(String refreshToken) {
        if (refreshToken == null) {
            // Wylogowanie bez tokenu nie jest błędem - kontroler i tak wyczyści ciasteczka.
            return;
        }
        refreshTokenService.revokeSession(refreshToken);
    }
}