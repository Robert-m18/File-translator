/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.robert.translation;

import com.example.robert.translation.exception.InvalidUploadException;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.Locale;

/**
 * Wgrany plik tekstowy po walidacji - nazwa i treść, obie już bezpieczne.
 *
 * Cała walidacja wejścia siedzi tutaj, a nie w kontrolerze, żeby ten pozostał samym HTTP.
 * Sprawdzamy DOKŁADNIE trzy rzeczy i to jest świadoma granica:
 *
 *  1. czy plik nie jest pusty,
 *  2. czy ma rozszerzenie .txt - a NIE, czy nagłówek Content-Type mówi text/plain:
 *     ten nagłówek ustawia klient i można w nim napisać cokolwiek,
 *  3. czy bajty dekodują się jako UTF-8.
 *
 * Punkt 3 nie jest formalnością. Plik zapisany w Windows-1250 (domyślne kodowanie polskiego
 * Notatnika przez lata) przepuszczony przez zwykłe new String(bytes, UTF_8) NIE wywala się -
 * daje tekst z bajtami zastępczymi w miejscu polskich znaków. Taki tekst zostałby wysłany
 * do dostawcy, przetłumaczony razem z krzakami i oddany użytkownikowi jako "tłumaczenie",
 * bez jednego błędu po drodze. Dlatego dekoder jest ustawiony na REPORT: ma rzucić wyjątek,
 * a nie zastępować.
 *
 * Czego tu świadomie NIE MA: wykrywania typu po zawartości (Tika), skanowania
 * antywirusowego i zgadywania kodowania z automatyczną konwersją. Zła strona kodowa to
 * czytelny błąd 400, a nie próba odgadnięcia, co użytkownik miał na myśli.
 */
public record UploadedTextFile(String filename, String content) {

    private static final String REQUIRED_EXTENSION = ".txt";

    public static UploadedTextFile from(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new InvalidUploadException("EMPTY_FILE", "Plik jest pusty albo nie został przesłany");
        }

        String filename = safeFilename(file.getOriginalFilename());

        if (!filename.toLowerCase(Locale.ROOT).endsWith(REQUIRED_EXTENSION)) {
            // Rozszerzenie, nie Content-Type: ten drugi przychodzi od klienta i nie jest
            // dowodem na nic. Na razie obsługujemy wyłącznie tekst.
            throw new InvalidUploadException("UNSUPPORTED_FILE_TYPE",
                    "Obsługiwane są wyłącznie pliki .txt");
        }

        byte[] bytes = read(file);
        String content = decodeUtf8(bytes);

        if (content.isBlank()) {
            // Plik z samymi białymi znakami jest technicznie niepusty, ale nie ma czego
            // tłumaczyć - lepiej odmówić od razu, niż zużyć limit znaków na spacje.
            throw new InvalidUploadException("EMPTY_FILE", "Plik nie zawiera tekstu do tłumaczenia");
        }

        return new UploadedTextFile(filename, content);
    }

    /**
     * Zostawia z nazwy sam ostatni segment.
     *
     * Klient może przysłać "../../etc/passwd" albo ścieżkę z dysku - i mimo że nigdy nie
     * dotykamy tą nazwą systemu plików, wraca ona do przeglądarki w nagłówku
     * Content-Disposition przy pobieraniu wyniku. Obcięcie do nazwy pliku jest tańsze niż
     * pamiętanie o tym w każdym miejscu, które tę wartość później wyświetli.
     */
    private static String safeFilename(String original) {
        if (original == null || original.isBlank()) {
            return "plik.txt";
        }
        // Ukośnik wsteczny zamieniany ręcznie: na systemie uniksowym Paths.get() nie uzna
        // go za separator, a przeglądarka na Windowsie potrafi przysłać całą ścieżkę.
        String normalized = original.replace('\\', '/');
        String name = Paths.get(normalized).getFileName().toString();
        return name.isBlank() ? "plik.txt" : name;
    }

    private static byte[] read(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new InvalidUploadException("EMPTY_FILE", "Nie udało się odczytać przesłanego pliku");
        }
    }

    /**
     * Dekodowanie z REPORT zamiast REPLACE - patrz uzasadnienie w opisie klasy.
     * new String(bytes, UTF_8) zrobiłby REPLACE i zamienił błąd w ciche uszkodzenie treści.
     */
    private static String decodeUtf8(byte[] bytes) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            CharBuffer decoded = decoder.decode(ByteBuffer.wrap(bytes));
            return decoded.toString();
        } catch (CharacterCodingException e) {
            throw new InvalidUploadException("INVALID_FILE_ENCODING",
                    "Plik nie jest zapisany w UTF-8. Zapisz go ponownie w kodowaniu UTF-8 i spróbuj jeszcze raz.");
        }
    }
}
