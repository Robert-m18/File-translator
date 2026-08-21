/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.filetranslator.translation;

import com.example.filetranslator.common.security.TokenHasher;
import com.example.filetranslator.translation.exception.InvalidUploadException;
import com.example.filetranslator.translation.model.FileType;
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
 * Wgrany plik po walidacji: bezpieczna nazwa, rozpoznany typ i bajty treści.
 *
 * Walidacja obejmuje cztery kroki, w tej kolejności: sprawdzenie, czy plik nie jest pusty;
 * rozpoznanie typu po sygnaturze w bajtach, a nie po rozszerzeniu ani nagłówku typu treści,
 * bo oba ustawia klient; kontrolę rozmiaru wobec limitu właściwego dla rozpoznanego typu; oraz -
 * dla plików tekstowych - sprawdzenie, czy bajty dekodują się jako UTF-8.
 *
 * Ostatni krok nie jest formalnością. Plik zapisany w kodowaniu jednobajtowym przepuszczony przez
 * zwykłą konwersję na tekst nie kończy się błędem, tylko daje tekst z bajtami zastępczymi
 * w miejscu znaków diakrytycznych. Taki tekst zostałby wysłany do dostawcy, przetłumaczony razem
 * z uszkodzonymi znakami i oddany użytkownikowi jako wynik, bez żadnego błędu po drodze. Dlatego
 * dekoder jest ustawiony na zgłaszanie błędów, a nie na zastępowanie znaków.
 *
 * Klasa świadomie nie robi trzech rzeczy: nie skanuje plików antywirusowo, nie otwiera archiwów
 * ani dokumentów i nie zgaduje kodowania z automatyczną konwersją. Nieotwieranie plików ma
 * konkretną korzyść bezpieczeństwa - formaty spakowane przechodzą przez aplikację jako
 * nieprzezroczyste bajty, więc bomby dekompresyjne nie są tu zagrożeniem.
 */
public record UploadedFile(String filename, FileType type, byte[] content) {

    public static UploadedFile from(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new InvalidUploadException("EMPTY_FILE", "Plik jest pusty albo nie został przesłany");
        }

        String filename = safeFilename(file.getOriginalFilename());
        byte[] bytes = read(file);

        FileType type = FileType.detect(bytes, filename)
                .orElseThrow(() -> new InvalidUploadException("UNSUPPORTED_FILE_TYPE",
                        "Obsługiwane formaty to " + FileType.allowedExtensions()));

        if (bytes.length > type.maxBytes()) {
            // Kontrola odrębna od globalnego limitu multiparta: tamten jest sufitem dla wszystkich
            // formatów i musi przepuścić największy z nich, więc sam nie odsiałby pliku
            // tekstowego o rozmiarze dopuszczalnym dla dokumentu.
            throw new InvalidUploadException("FILE_TOO_LARGE_FOR_TYPE",
                    "Plik %s może mieć najwyżej %d KB".formatted(type.extension(), type.maxBytes() / 1024));
        }

        if (type == FileType.TXT) {
            String text = decodeUtf8(bytes);
            if (text.isBlank()) {
                // Plik z samymi białymi znakami jest technicznie niepusty, ale nie ma w nim czego
                // tłumaczyć - odmowa jest tańsza niż zużycie limitu znaków na spacje.
                throw new InvalidUploadException("EMPTY_FILE", "Plik nie zawiera tekstu do tłumaczenia");
            }
        }

        return new UploadedFile(filename, type, bytes);
    }

    /**
     * Zwraca treść tekstową - wyłącznie dla plików tekstowych.
     *
     * Dla formatów binarnych rzuca wyjątek zamiast zwracać uszkodzony łańcuch: wywołanie tej
     * metody na dokumencie oznacza, że ktoś skierował go na ścieżkę tekstową, a to błąd
     * programistyczny do naprawienia, a nie sytuacja do obsłużenia w czasie działania.
     */
    public String text() {
        if (type != FileType.TXT) {
            throw new IllegalStateException("Treść tekstowa dostępna wyłącznie dla plików " + FileType.TXT.extension());
        }
        return new String(content, StandardCharsets.UTF_8);
    }

    /**
     * Zwraca liczbę znaków stanowiącą podstawę dobowego limitu. Dla dokumentów wynosi zero.
     *
     * Nie jest to brak implementacji, tylko własność formatu: liczby znaków w dokumencie nie da
     * się poznać bez jego otwarcia, czego ta klasa świadomie unika. Rzeczywistą wartość podaje
     * dostawca dopiero po przetłumaczeniu i wtedy trafia ona do wiersza zlecenia. Konsekwencją
     * przyjętą świadomie jest to, że jeden dokument może przekroczyć dobowy limit użytkownika,
     * ponieważ sprawdzić da się wyłącznie zużycie z przeszłości; szkodę ogranicza limit rozmiaru
     * ustalony osobno dla każdego formatu.
     */
    public int charCount() {
        return type == FileType.TXT ? text().length() : 0;
    }

    /**
     * Zwraca odcisk treści używany przy deduplikacji - ten sam plik wgrany ponownie daje ten sam
     * skrót.
     *
     * Skrót liczony jest z bajtów, nie z tekstu: dla formatów binarnych nie ma innej możliwości,
     * a dla tekstu wynik jest identyczny, więc odciski zapisane wcześniej pozostają trafne
     * i deduplikacja nie traci ważności.
     *
     * Obliczenie wykonuje wspólna klasa pomocnicza, a nie prywatna kopia algorytmu w tym pakiecie:
     * dwie kopie tego samego skrótu rozjechałyby się po cichu przy pierwszej zmianie jednej z nich.
     *
     * Skrót liczony jest z treści dokładnie takiej, jaka trafi do tłumaczenia - bez normalizacji
     * końców linii i znacznika kolejności bajtów. Plik różniący się samym rodzajem końca linii da
     * inny odcisk i po prostu nie trafi w cache.
     */
    public String contentHash() {
        return TokenHasher.sha256Hex(content);
    }

    /**
     * Zostawia z przysłanej nazwy sam ostatni segment ścieżki.
     *
     * Klient może przysłać ścieżkę względną albo pełną ścieżkę z dysku. Nazwa nie buduje klucza
     * w magazynie - ten powstaje z identyfikatora użytkownika, losowego identyfikatora
     * i rozszerzenia z zamkniętego zbioru - ale wraca do przeglądarki w nagłówku pobierania i jedzie
     * do dostawcy jako nazwa dokumentu. Obcięcie w jednym miejscu jest tańsze niż pamiętanie
     * o nim w każdym z tych zastosowań.
     */
    private static String safeFilename(String original) {
        if (original == null || original.isBlank()) {
            return "plik";
        }
        // Ukośnik wsteczny zamieniany ręcznie: na systemie uniksowym nie zostałby uznany za
        // separator, a przeglądarka na Windowsie potrafi przysłać całą ścieżkę.
        String normalized = original.replace('\\', '/');
        String name = Paths.get(normalized).getFileName().toString();
        return name.isBlank() ? "plik" : name;
    }

    private static byte[] read(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new InvalidUploadException("EMPTY_FILE", "Nie udało się odczytać przesłanego pliku");
        }
    }

    /**
     * Dekoduje bajty jako UTF-8, zgłaszając błąd zamiast zastępowania znaków.
     *
     * Zwykła konwersja na tekst zastąpiłaby nieprawidłowe bajty znakiem zastępczym i zamieniła
     * błąd w ciche uszkodzenie treści, które wyszłoby na jaw dopiero w tłumaczeniu oddanym
     * użytkownikowi.
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

    /** Nazwa pliku bez rozszerzenia - służy do zbudowania nazwy pliku wynikowego. */
    public String baseName() {
        String lower = filename.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf(type.extension());
        return dot > 0 ? filename.substring(0, dot) : filename;
    }
}
