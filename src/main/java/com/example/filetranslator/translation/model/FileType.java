/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.filetranslator.translation.model;

import java.util.Locale;
import java.util.Optional;

/**
 * Obsługiwane formaty plików - zamknięty zbiór, z którego bierze się rozszerzenie klucza
 * w magazynie, typ treści odpowiedzi, limit rozmiaru i sposób tłumaczenia.
 *
 * DWIE ŚCIEŻKI TŁUMACZENIA. Tekst idzie zwykłym API tłumaczącym łańcuch znaków: jest
 * synchroniczne, tańsze i pokryte testami od początku. Formaty binarne idą DOKUMENTOWYM API
 * dostawcy, bo wyciąganie tekstu z PDF-a i składanie go z powrotem gubi układ - użytkownik
 * dostałby plik .txt zamiast przetłumaczonego dokumentu. Konsekwencja uboczna, którą warto
 * znać: skoro NIE otwieramy tych plików, bomby dekompresyjne (XLSX to ZIP) nie są naszą
 * ekspozycją. Wracają w chwili, w której ktoś sięgnie po PDFBox albo POI.
 *
 * LIMITY ROZMIARU SĄ RÓŻNE DLA TEKSTU I DLA BINARIÓW, i to nie jest niekonsekwencja:
 *
 * - Dla .txt bajty to praktycznie znaki, więc limit da się WYPROWADZIĆ z ekonomii dostawcy.
 *   256 KB przy miesięcznym budżecie 500 tys. znaków na całe konto DeepL Free zostaje bez
 *   zmian - dwa takie pliki to już ponad połowa miesiąca dla wszystkich użytkowników razem.
 * - Dla PDF-a, DOCX-a i XLSX-a takiej zależności NIE MA: liczba znaków jest nieznana, dopóki dostawca
 *   nie odeśle billed_characters. Limit bajtowy nie chroni więc budżetu, tylko OGRANICZA
 *   SZKODĘ z jednego pliku. 2 MB, czyli wyraźnie poniżej 10 MB, które dopuszcza DeepL -
 *   schodzimy niżej świadomie, bo u nas jeden dokument może jednorazowo przekroczyć dobowy
 *   limit znaków użytkownika (sprawdzić da się dopiero wstecz, po fakcie).
 */
public enum FileType {

    TXT(".txt", "text/plain; charset=UTF-8", 256 * 1024, false),
    PDF(".pdf", "application/pdf", 2 * 1024 * 1024, true),
    DOCX(".docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            2 * 1024 * 1024, true),
    XLSX(".xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            2 * 1024 * 1024, true);

    /** Sygnatura PDF-a: pierwsze bajty pliku to zawsze "%PDF-". */
    private static final byte[] PDF_MAGIC = {'%', 'P', 'D', 'F', '-'};

    /** Sygnatura archiwum ZIP - a XLSX i DOCX są archiwami ZIP. */
    private static final byte[] ZIP_MAGIC = {'P', 'K', 0x03, 0x04};

    private final String extension;
    private final String contentType;
    private final int maxBytes;
    private final boolean document;

    FileType(String extension, String contentType, int maxBytes, boolean document) {
        this.extension = extension;
        this.contentType = contentType;
        this.maxBytes = maxBytes;
        this.document = document;
    }

    public String extension() {
        return extension;
    }

    public String contentType() {
        return contentType;
    }

    public int maxBytes() {
        return maxBytes;
    }

    /** Czy tłumaczy się dokumentowym API dostawcy (zamiast zwykłym, tekstowym). */
    public boolean usesDocumentApi() {
        return document;
    }

    /**
     * Rozpoznaje typ po ZAWARTOŚCI, a nie po rozszerzeniu ani po nagłówku Content-Type.
     *
     * Rozszerzenie i Content-Type ustawia klient i można w nich napisać cokolwiek - to jest
     * cała różnica między "plik nazywa się .pdf" a "plik JEST PDF-em". Poprzednia wersja
     * (UploadedTextFile) świadomie tego nie robiła, bo przy jednym formacie tekstowym
     * dekodowanie UTF-8 samo w sobie było kontrolą zawartości. Przy binariach ta kontrola
     * znika i trzeba ją zastąpić sygnaturą.
     *
     * OGRANICZENIE, KTÓRE TRZEBA ZNAĆ: XLSX, DOCX i PPTX mają IDENTYCZNĄ sygnaturę, bo
     * wszystkie są archiwami ZIP. Odróżnienie ich wymaga otwarcia archiwum i zajrzenia do
     * środka - czyli dokładnie tego, czego nie robimy, żeby nie wpuścić sobie bomb
     * dekompresyjnych. Dlatego dla archiwum ZIP decyduje rozszerzenie.
     *
     * ODKĄD OBSŁUGIWANE SĄ DWA FORMATY ZIP-owe (XLSX i DOCX), ROZSZERZENIE JEST JEDYNĄ RZECZĄ,
     * KTÓRA JE OD SIEBIE ODRÓŻNIA - i to jest cała cena decyzji o nieotwieraniu archiwów.
     * Dopóki był tu jeden taki format, pomylenie się kończyło odrzuceniem pliku u nas. Teraz
     * arkusz nazwany .docx przejdzie naszą kontrolę i pojedzie do dostawcy jako dokument
     * Worda - odrzuci go dopiero on, błędem TRWAŁYM (retryable=false), więc zlecenie kończy
     * się na pierwszej próbie i nie chodzi przez pełne wycofywanie. Objawem dla użytkownika
     * jest komunikat od dostawcy, a nie nasza awaria - i tak ma być, bo to jego plik jest
     * niezgodny z własną nazwą.
     *
     * @return typ albo pusty Optional, jeśli zawartość nie pasuje do żadnego obsługiwanego
     */
    public static Optional<FileType> detect(byte[] content, String filename) {
        if (startsWith(content, PDF_MAGIC)) {
            return Optional.of(PDF);
        }
        if (startsWith(content, ZIP_MAGIC)) {
            String lower = filename.toLowerCase(Locale.ROOT);
            if (lower.endsWith(XLSX.extension)) {
                return Optional.of(XLSX);
            }
            if (lower.endsWith(DOCX.extension)) {
                return Optional.of(DOCX);
            }
            // Każde inne archiwum (.zip, .pptx, .odt) odrzucamy. Lista dozwolonych, a nie
            // "cokolwiek jest ZIP-em": bez tego dowolne archiwum poszłoby do dostawcy.
            return Optional.empty();
        }
        // Tekst nie ma sygnatury - rozstrzyga go dopiero próba zdekodowania jako UTF-8,
        // którą robi UploadedFile. Tutaj mówimy tylko, że nie jest to żaden ze znanych binariów.
        return Optional.of(TXT);
    }

    private static boolean startsWith(byte[] content, byte[] magic) {
        if (content.length < magic.length) {
            return false;
        }
        for (int i = 0; i < magic.length; i++) {
            if (content[i] != magic[i]) {
                return false;
            }
        }
        return true;
    }

    /** Do komunikatu błędu - użytkownik ma wiedzieć, co wolno wgrać. */
    public static String allowedExtensions() {
        StringBuilder allowed = new StringBuilder();
        for (FileType type : values()) {
            if (!allowed.isEmpty()) {
                allowed.append(", ");
            }
            allowed.append(type.extension);
        }
        return allowed.toString();
    }
}
