/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.filetranslator.common.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Liczy skrót SHA-256 zapisywany w bazie zamiast wartości oryginalnej.
 *
 * Mimo nazwy służy do dwóch rzeczy: skrótów tokenów oraz odcisku treści wgranego pliku, po którym
 * rozpoznawane są powtórzone tłumaczenia. Istotne jest to, że algorytm ma w całej aplikacji
 * dokładnie jedno miejsce - druga kopia rozjechałaby się po cichu przy pierwszej zmianie jednej
 * z nich.
 *
 * Obowiązująca zasada: w bazie nigdy nie leży token, którym da się posłużyć. Przechowywany jest
 * jego skrót, a przy weryfikacji skrót liczony jest z wartości przysłanej przez klienta
 * i porównywany. Dzięki temu wyciek zawartości bazy nie daje działających tokenów.
 *
 * Użyty jest zwykły SHA-256, a nie funkcja spowalniająca stosowana przy hasłach, ponieważ token
 * jest ciągiem losowym o dużej entropii, więc atak słownikowy na niego nie działa. Spowalnianie
 * nic by tu nie wniosło, a kosztowałoby przy każdym odświeżeniu sesji. Przy hasłach wymyślanych
 * przez ludzi zależność jest odwrotna i tam używana jest funkcja spowalniająca.
 */
public final class TokenHasher {

    private TokenHasher() {
    }

    public static String sha256Hex(String rawToken) {
        return sha256Hex(rawToken.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Wariant dla surowych bajtów - używany do odcisku wgranego pliku.
     *
     * Konieczny przy formatach binarnych, których nie da się zamienić na tekst bez uszkodzenia,
     * a odcisk musi być liczony dokładnie z tych bajtów, które trafią do dostawcy. Dla plików
     * tekstowych wynik jest identyczny z wariantem powyżej, bo tamten koduje łańcuch do UTF-8
     * i woła tę metodę.
     */
    public static String sha256Hex(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content));
        } catch (NoSuchAlgorithmException e) {
            // Nie wystąpi - obecność tego algorytmu jest wymagana przez specyfikację każdej JVM.
            throw new IllegalStateException("Brak algorytmu SHA-256", e);
        }
    }
}
