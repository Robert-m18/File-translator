/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.robert.translation;

import com.example.robert.common.security.TokenHasher;
import com.example.robert.translation.exception.InvalidUploadException;
import com.example.robert.translation.model.FileType;
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
 * Wgrany plik po walidacji: nazwa, rozpoznany typ i bajty.
 *
 * Poprzednia wersja tej klasy (UploadedTextFile) trzymała treść jako String i świadomie
 * NIE rozpoznawała typu po zawartości - przy jednym formacie tekstowym samo dekodowanie
 * UTF-8 było wystarczającą kontrolą, a rozszerzenie służyło tylko do odsiania oczywistych
 * pomyłek. Formaty binarne zmieniają oba te założenia: PDF-a nie da się trzymać jako String
 * bez uszkodzenia go, a dekodowanie przestaje być kontrolą zawartości.
 *
 * SPRAWDZAMY CZTERY RZECZY, w tej kolejności:
 *  1. czy plik nie jest pusty,
 *  2. jaki to TYP - po sygnaturze w bajtach, a NIE po rozszerzeniu ani po nagłówku
 *     Content-Type, bo oba ustawia klient (ograniczenia rozpoznawania: FileType.detect),
 *  3. czy mieści się w limicie rozmiaru SWOJEGO typu (uzasadnienie różnych limitów: FileType),
 *  4. dla tekstu dodatkowo: czy bajty dekodują się jako UTF-8.
 *
 * Punkt 4 zostaje bez zmian i nie jest formalnością. Plik zapisany w Windows-1250 (domyślne
 * kodowanie polskiego Notatnika przez lata) przepuszczony przez zwykłe new String(bytes, UTF_8)
 * NIE wywala się - daje tekst z bajtami zastępczymi w miejscu polskich znaków. Taki tekst
 * zostałby wysłany do dostawcy, przetłumaczony razem z krzakami i oddany użytkownikowi jako
 * "tłumaczenie", bez jednego błędu po drodze. Dekoder jest więc ustawiony na REPORT.
 *
 * Czego tu świadomie NIE MA: skanowania antywirusowego, otwierania archiwów i zgadywania
 * kodowania z automatyczną konwersją.
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
            // Osobno od globalnego limitu multiparta (413 FILE_TOO_LARGE): tamten jest
            // sufitem dla WSZYSTKICH formatów i musi przepuścić największy z nich, więc
            // nie odsieje pliku .txt o rozmiarze dopuszczalnym dla PDF-a.
            throw new InvalidUploadException("FILE_TOO_LARGE_FOR_TYPE",
                    "Plik %s może mieć najwyżej %d KB".formatted(type.extension(), type.maxBytes() / 1024));
        }

        if (type == FileType.TXT) {
            String text = decodeUtf8(bytes);
            if (text.isBlank()) {
                // Plik z samymi białymi znakami jest technicznie niepusty, ale nie ma czego
                // tłumaczyć - lepiej odmówić od razu, niż zużyć limit znaków na spacje.
                throw new InvalidUploadException("EMPTY_FILE", "Plik nie zawiera tekstu do tłumaczenia");
            }
        }

        return new UploadedFile(filename, type, bytes);
    }

    /**
     * Treść tekstowa - wyłącznie dla plików TXT.
     *
     * Rzuca dla binariów świadomie, zamiast oddawać uszkodzony łańcuch: wywołanie tej metody
     * na PDF-ie oznacza, że ktoś skierował dokument na ścieżkę tekstową, a to jest błąd
     * programistyczny do naprawienia, a nie sytuacja do obsłużenia w czasie działania.
     */
    public String text() {
        if (type != FileType.TXT) {
            throw new IllegalStateException("Treść tekstowa dostępna wyłącznie dla plików " + FileType.TXT.extension());
        }
        return new String(content, StandardCharsets.UTF_8);
    }

    /**
     * Liczba znaków - podstawa dobowego limitu. Dla dokumentów NIEZNANA w tym miejscu.
     *
     * To nie jest brak implementacji, tylko własność formatu: liczby znaków w PDF-ie nie da
     * się poznać bez otwarcia go, a otwierania unikamy świadomie (patrz FileType). Prawdziwą
     * wartość podaje dostawca jako billed_characters dopiero po przetłumaczeniu i dopiero
     * wtedy trafia ona do wiersza. Konsekwencja przyjęta świadomie: JEDEN dokument może
     * przekroczyć dobowy limit użytkownika, bo sprawdzić da się wyłącznie zużycie z przeszłości.
     * Szkodę z tego ogranicza limit rozmiaru per format.
     */
    public int charCount() {
        return type == FileType.TXT ? text().length() : 0;
    }

    /**
     * Odcisk treści pod deduplikację - ten sam plik wgrany ponownie ma dać ten sam skrót.
     *
     * Liczony z BAJTÓW, nie z tekstu: dla binariów nie ma innej możliwości, a dla tekstu
     * wynik jest identyczny jak wcześniej (dawna wersja hashowała String, który i tak był
     * kodowany do UTF-8 wewnątrz TokenHasher). Odciski zapisane przed tą zmianą pozostają
     * więc trafne i cache nie traci ważności.
     *
     * Skrót liczy TokenHasher, a nie prywatna kopia MessageDigest w tym pakiecie: AuthService
     * miał kiedyś własny duplikat tego samego algorytmu obok klasy współdzielonej i obie
     * ścieżki rozjechałyby się po cichu przy pierwszej zmianie jednej z nich.
     *
     * Uwaga na zakres: liczymy skrót z treści DOKŁADNIE takiej, jaka pójdzie do tłumaczenia -
     * bez normalizacji końców linii, BOM-u czy końcowej pustej linii. Plik różniący się samym
     * CRLF-em da inny odcisk i po prostu spudłuje.
     */
    public String contentHash() {
        return TokenHasher.sha256Hex(content);
    }

    /**
     * Zostawia z nazwy sam ostatni segment.
     *
     * Klient może przysłać "../../etc/passwd" albo ścieżkę z dysku. Nazwa nie buduje klucza
     * w magazynie (ten składa ObjectKeys z identyfikatora użytkownika, UUID i rozszerzenia
     * z wyliczenia), ale wraca do przeglądarki w nagłówku Content-Disposition i jedzie
     * do dostawcy jako nazwa dokumentu. Obcięcie do nazwy pliku jest tańsze niż pamiętanie
     * o tym w każdym z tych miejsc.
     */
    private static String safeFilename(String original) {
        if (original == null || original.isBlank()) {
            return "plik";
        }
        // Ukośnik wsteczny zamieniany ręcznie: na systemie uniksowym Paths.get() nie uzna
        // go za separator, a przeglądarka na Windowsie potrafi przysłać całą ścieżkę.
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

    /** Nazwa pliku bez rozszerzenia - do zbudowania nazwy wyniku. */
    public String baseName() {
        String lower = filename.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf(type.extension());
        return dot > 0 ? filename.substring(0, dot) : filename;
    }
}
