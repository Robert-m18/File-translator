/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.robert.auth;

import com.example.robert.common.security.TokenHasher;
import com.example.robert.common.exception.JwtAuthenticationException;
import com.example.robert.auth.model.RefreshToken;
import com.example.robert.user.model.User;
import com.example.robert.auth.repository.RefreshTokenRepository;
import com.example.robert.user.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;

/**
 * Zarządza cyklem życia tokenów odświeżających: wydanie, rotacja, unieważnienie.
 *
 * Zaimplementowany wzorzec to "refresh token rotation with reuse detection"
 * (rekomendacja OAuth 2.0 Security Best Current Practice):
 *
 *  1. Każde użycie tokenu odświeżającego zużywa go i wydaje nowy - token działa raz.
 *  2. Wszystkie tokeny z kolejnych rotacji jednej sesji mają wspólny family_id.
 *  3. Próba użycia tokenu już zużytego oznacza, że ktoś ma jego kopię - albo klient
 *     ponawia żądanie, albo token wyciekł. Nie da się tego rozróżnić, więc traktujemy
 *     to jako włamanie i unieważniamy CAŁĄ rodzinę.
 *
 * Efekt: napastnik, który ukradł token, może z niego skorzystać najwyżej raz, a przy
 * najbliższym odświeżeniu przez prawowitego użytkownika (albo odwrotnie) sesja pada
 * dla obu stron. Bez rotacji skradziony token działałby po cichu przez pełne 7 dni.
 */
@Slf4j
@Service
public class RefreshTokenService {

    private final RefreshTokenRepository refreshTokenRepository;
    private final UserRepository userRepository;
    private final long refreshExpirationMillis;

    /**
     * Szablon transakcji uruchamianej niezależnie od bieżącej (PROPAGATION_REQUIRES_NEW).
     *
     * Potrzebny, bo unieważnienie rodziny po wykryciu kradzieży musi zostać ZATWIERDZONE,
     * a zaraz po nim rzucamy wyjątek - który wycofuje transakcję odświeżania. W jednej
     * transakcji unieważnienie zniknęłoby razem z nią i skradziony token dalej by działał,
     * czyli cały mechanizm byłby pozorny.
     *
     * Programowo, a nie przez @Transactional(REQUIRES_NEW) na metodzie tej samej klasy:
     * wywołanie własnej metody omija proxy Springa, więc adnotacja nie zadziałałaby wcale.
     * To jedna z najczęstszych pułapek przy transakcjach w Springu.
     */
    private final TransactionTemplate independentTransaction;

    public RefreshTokenService(RefreshTokenRepository refreshTokenRepository,
                               UserRepository userRepository,
                               PlatformTransactionManager transactionManager,
                               @Value("${jwt.refresh-expiration}") long refreshExpirationMillis) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.userRepository = userRepository;
        this.refreshExpirationMillis = refreshExpirationMillis;

        this.independentTransaction = new TransactionTemplate(transactionManager);
        this.independentTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Rejestruje token wydany przy logowaniu i otwiera nową rodzinę (nowa sesja/urządzenie).
     */
    @Transactional
    public void startSession(String email, String rawRefreshToken) {
        // Użytkownik wyszukiwany po emailu, a nie brany z Authentication.getPrincipal().
        // Rzutowanie principala na naszą encję User działa dopóki jedynym źródłem tożsamości
        // jest UserDetailsService - po dołożeniu logowania przez Google principalem będzie
        // OidcUser i takie rzutowanie by się wywaliło.
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalStateException(
                        "Uwierzytelniony użytkownik nie istnieje w bazie"));

        persist(rawRefreshToken, user, UUID.randomUUID().toString());
    }

    /**
     * Sprawdza token przedstawiony przy odświeżaniu i zużywa go, wydając następny w rodzinie.
     */
    @Transactional
    public void rotate(String presentedRawToken, String newRawToken) {
        RefreshToken current = loadAndValidate(presentedRawToken);

        Instant now = Instant.now();
        current.setRevokedAt(now); // encja jest zarządzana - dirty checking zapisze zmianę

        persist(newRawToken, current.getUser(), current.getFamilyId());
    }

    /**
     * Wylogowanie: unieważnia całą rodzinę, czyli sesję tego urządzenia.
     * Sesje na innych urządzeniach zostają nietknięte, bo mają własne family_id.
     */
    @Transactional
    public void revokeSession(String rawRefreshToken) {
        refreshTokenRepository.findByTokenHash(TokenHasher.sha256Hex(rawRefreshToken))
                .ifPresent(token -> {
                    int revoked = refreshTokenRepository.revokeFamily(token.getFamilyId(), Instant.now());
                    log.info("Wylogowanie - unieważniono {} token(ów) odświeżających", revoked);
                });
        // Brak tokenu w bazie nie jest błędem: wylogowanie ma być idempotentne,
        // a klient i tak dostanie polecenie skasowania ciasteczek.
    }

    /**
     * Unieważnia wszystkie sesje użytkownika na wszystkich urządzeniach.
     * Używane przy resecie hasła - patrz RefreshTokenRepository.revokeAllForUser.
     *
     * @return liczba unieważnionych tokenów
     */
    @Transactional
    public int revokeAllSessions(Long userId) {
        return refreshTokenRepository.revokeAllForUser(userId, Instant.now());
    }

    private RefreshToken loadAndValidate(String rawToken) {
        RefreshToken token = refreshTokenRepository.findByTokenHash(TokenHasher.sha256Hex(rawToken))
                .orElseThrow(() -> {
                    // Podpis JWT był poprawny, ale rekordu nie ma - token został wyczyszczony
                    // jako wygasły albo pochodzi z bazy sprzed resetu.
                    log.warn("Przedstawiono nieznany token odświeżający");
                    return new JwtAuthenticationException(
                            "Sesja wygasła, zaloguj się ponownie", "REFRESH_TOKEN_UNKNOWN");
                });

        if (token.isRevoked()) {
            String familyId = token.getFamilyId();
            Integer revoked = independentTransaction.execute(status ->
                    refreshTokenRepository.revokeFamily(familyId, Instant.now()));

            log.warn("Wykryto ponowne użycie zużytego tokenu odświeżającego (familyId={}). "
                    + "Unieważniono całą rodzinę: {} token(ów)", familyId, revoked);
            throw new JwtAuthenticationException(
                    "Wykryto ponowne użycie tokenu - sesja została unieważniona", "REFRESH_TOKEN_REUSED");
        }

        if (token.isExpired()) {
            throw new JwtAuthenticationException("Sesja wygasła, zaloguj się ponownie", "EXPIRED_TOKEN");
        }

        return token;
    }

    private void persist(String rawToken, User user, String familyId) {
        Instant now = Instant.now();
        refreshTokenRepository.save(new RefreshToken(
                TokenHasher.sha256Hex(rawToken),
                user,
                familyId,
                now,
                now.plusNanos(refreshExpirationMillis * 1_000_000)
        ));
    }
}
