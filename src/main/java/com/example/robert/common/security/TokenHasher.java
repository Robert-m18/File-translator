/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.robert.common.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Liczy SHA-256 tokenu zapisywany w bazie.
 *
 * MIMO NAZWY służy też do czegoś drugiego: UploadedTextFile liczy nią odcisk treści wgranego
 * pliku pod deduplikację tłumaczeń. Nazwa została, bo tokeny są tu głównym zastosowaniem,
 * a przemianowanie ruszyłoby cały moduł auth przy okazji niezwiązanej zmiany. Istotne jest to,
 * że algorytm ma w projekcie DOKŁADNIE jedno miejsce - druga kopia rozjechałaby się po cichu.
 *
 * Zasada: w bazie nigdy nie leży token, którym da się posłużyć. Trzymamy jego skrót,
 * a przy weryfikacji hashujemy to, co przyszło od klienta, i porównujemy hashe.
 * Wyciek dumpu bazy nie daje wtedy działających tokenów.
 *
 * Dlaczego zwykłe SHA-256, a nie BCrypt jak przy hasłach: token to losowe 128+ bitów,
 * więc nie da się go zgadnąć atakiem słownikowym - spowalnianie funkcji skrótu nic tu
 * nie wnosi, a kosztowałoby przy każdym odświeżeniu tokenu. Przy hasłach, które ludzie
 * wymyślają sami, jest dokładnie odwrotnie i tam BCrypt zostaje.
 */
public final class TokenHasher {

    private TokenHasher() {
    }

    public static String sha256Hex(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException e) {
            // Nie wystąpi - SHA-256 jest wymagane przez specyfikację każdej JVM
            throw new IllegalStateException("Brak algorytmu SHA-256", e);
        }
    }
}
