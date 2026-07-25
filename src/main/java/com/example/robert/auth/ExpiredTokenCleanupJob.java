/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.robert.auth;

import com.example.robert.auth.repository.RefreshTokenRepository;
import com.example.robert.auth.repository.VerificationTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Nocne sprzątanie wygasłych tokenów.
 *
 * UWAGA na wdrożenie wieloinstancyjne: @Scheduled odpala się w każdej instancji osobno,
 * więc przy dwóch podach job wykona się dwa razy. Tutaj jest to nieszkodliwe (usuwanie
 * jest idempotentne), ale przy zadaniach o skutkach ubocznych - jak wysyłka maili -
 * trzeba dołożyć blokadę rozproszoną (ShedLock).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExpiredTokenCleanupJob {

    private final VerificationTokenRepository verificationTokenRepository;
    private final RefreshTokenRepository refreshTokenRepository;

    // Co noc o 3:00
    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void cleanupExpiredTokens() {
        LocalDateTime now = LocalDateTime.now();

        // Tokeny weryfikacyjne: po wygaśnięciu i tak nie da się nimi potwierdzić konta.
        verificationTokenRepository.deleteAllExpired(now);

        // Tokeny odświeżające: usuwamy też te zużyte przez rotację, bo po wygaśnięciu
        // nie niosą już żadnej informacji. Bez tego tabela rosłaby w nieskończoność,
        // a wyszukiwanie po token_hash z czasem by zwalniało.
        int removedRefreshTokens = refreshTokenRepository.deleteAllExpired(now);

        log.info("Sprzątanie tokenów zakończone - usunięto {} wygasłych tokenów odświeżających",
                removedRefreshTokens);
    }
}
