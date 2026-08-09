/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.robert.auth;

import com.example.robert.auth.repository.PasswordResetTokenRepository;
import com.example.robert.auth.repository.PendingRegistrationRepository;
import com.example.robert.auth.repository.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Nocne sprzątanie wygasłych tokenów i porzuconych zgłoszeń rejestracji.
 *
 * Nie ma tu już kroku kasującego niepotwierdzone konta z tabeli users - po przeniesieniu
 * poczekalni do pending_registrations wiersz w users powstaje wyłącznie jako konto
 * potwierdzone, więc taki krok nie miałby czego szukać. Co więcej, byłby groźny: gdyby
 * ktoś dołożył kiedyś zakładanie kont przez administratora z enabled=false, job po cichu
 * kasowałby je po dobie.
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

    private final PendingRegistrationRepository pendingRegistrationRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final RefreshTokenRepository refreshTokenRepository;

    // Co noc o 3:00
    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void cleanupExpiredTokens() {
        Instant now = Instant.now();

        // Porzucone zgłoszenia rejestracji: nikt nie kliknął linku w ciągu doby.
        // Adresu nie blokowały (unikalność obowiązuje dopiero w users), ale tabela
        // rosłaby bez końca, a przy ataku na endpoint rejestracji rosłaby szybko.
        int removedRegistrations = pendingRegistrationRepository.deleteAllExpired(now);

        // Tokeny resetu hasła: kasujemy też zużyte, bo po wygaśnięciu nie są już
        // potrzebne nawet do komunikatu "link wykorzystany".
        int removedResetTokens = passwordResetTokenRepository.deleteAllExpired(now);

        // Tokeny odświeżające: usuwamy też te zużyte przez rotację, bo po wygaśnięciu
        // nie niosą już żadnej informacji. Bez tego tabela rosłaby w nieskończoność,
        // a wyszukiwanie po token_hash z czasem by zwalniało.
        int removedRefreshTokens = refreshTokenRepository.deleteAllExpired(now);

        log.info("Sprzątanie zakończone - usunięto {} porzuconych zgłoszeń rejestracji, "
                        + "{} tokenów resetu hasła i {} tokenów odświeżających",
                removedRegistrations, removedResetTokens, removedRefreshTokens);
    }
}
