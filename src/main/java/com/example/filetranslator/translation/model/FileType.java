/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.filetranslator.translation.model;

import java.util.Locale;
import java.util.Optional;

/**
 * Obsługiwane formaty plików - zamknięty zbiór, z którego pochodzi rozszerzenie klucza
 * w magazynie, typ treści odpowiedzi, limit rozmiaru i sposób tłumaczenia.
 *
 * Istnieją dwie ścieżki tłumaczenia. Tekst idzie interfejsem tłumaczącym łańcuch znaków: jest
 * synchroniczny i tańszy. Formaty binarne idą interfejsem dokumentowym dostawcy, ponieważ
 * wyciąganie tekstu z dokumentu i składanie go z powrotem gubi układ - użytkownik dostałby plik
 * tekstowy zamiast przetłumaczonego dokumentu. Ubocznym skutkiem nieotwierania tych plików jest
 * korzyść bezpieczeństwa: formaty spakowane przechodzą przez aplikację jako nieprzezroczyste
 * bajty, więc bomby dekompresyjne nie są tu zagrożeniem.
 *
 * Limity rozmiaru różnią się między tekstem a formatami binarnymi i nie jest to niekonsekwencja:
 *
 * - Dla pliku tekstowego bajty odpowiadają praktycznie znakom, więc limit da się wyprowadzić
 *   z ekonomii dostawcy, u którego rozlicza się właśnie znaki.
 * - Dla dokumentów takiej zależności nie ma: liczba znaków pozostaje nieznana, dopóki dostawca
 *   nie odeśle rozliczenia. Limit bajtowy nie chroni więc budżetu, tylko ogranicza szkodę
 *   z pojedynczego pliku, i jest wyraźnie niższy niż limit dopuszczany przez dostawcę - bo jeden
 *   dokument może jednorazowo przekroczyć dobowy limit znaków użytkownika, który da się sprawdzić
 *   dopiero po fakcie.
 */
public enum FileType {

    TXT(".txt", "text/plain; charset=UTF-8", 256 * 1024, false),
    PDF(".pdf", "application/pdf", 2 * 1024 * 1024, true),
    DOCX(".docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            2 * 1024 * 1024, true),
    XLSX(".xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            2 * 1024 * 1024, true);

    /** Sygnatura dokumentu PDF - pierwsze bajty pliku. */
    private static final byte[] PDF_MAGIC = {'%', 'P', 'D', 'F', '-'};

    /** Sygnatura archiwum ZIP; formaty pakietu Office są archiwami ZIP. */
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

    /** Czy format tłumaczy się dokumentowym interfejsem dostawcy zamiast tekstowym. */
    public boolean usesDocumentApi() {
        return document;
    }

    /**
     * Rozpoznaje typ pliku po zawartości, a nie po rozszerzeniu ani nagłówku typu treści.
     *
     * Rozszerzenie i typ treści ustawia klient i może w nich podać cokolwiek - na tym polega
     * różnica między "plik nazywa się dokumentem" a "plik jest dokumentem". Rozpoznanie po
     * sygnaturze sprawia, że plik binarny nie przejdzie ścieżką tekstową ani odwrotnie.
     *
     * Ograniczenie, które trzeba znać: formaty pakietu Office mają identyczną sygnaturę, bo
     * wszystkie są archiwami ZIP. Odróżnienie ich wymagałoby otwarcia archiwum, czego ta klasa
     * świadomie unika, dlatego dla archiwum decyduje rozszerzenie.
     *
     * Odkąd obsługiwane są dwa formaty spakowane, rozszerzenie jest jedyną rzeczą, która je od
     * siebie odróżnia, i to jest cała cena decyzji o nieotwieraniu archiwów. Plik nazwany
     * niezgodnie ze swoją zawartością przejdzie tę kontrolę i zostanie odrzucony dopiero przez
     * dostawcę, jako błąd trwały - zlecenie kończy się wtedy na pierwszej próbie, bez pełnego
     * cyklu ponowień, a użytkownik dostaje komunikat dotyczący jego pliku, a nie awarii usługi.
     *
     * @return rozpoznany typ albo pusty wynik, jeśli zawartość nie pasuje do żadnego obsługiwanego
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
            // Pozostałe archiwa są odrzucane. Lista dozwolonych zamiast reguły "cokolwiek jest
            // archiwum": bez niej dowolne archiwum trafiłoby do dostawcy.
            return Optional.empty();
        }
        // Tekst nie ma sygnatury - rozstrzyga go dopiero próba zdekodowania jako UTF-8, którą
        // wykonuje walidacja wgrywanego pliku. Tutaj stwierdzane jest wyłącznie to, że plik nie
        // jest żadnym ze znanych formatów binarnych.
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

    /** Lista rozszerzeń do komunikatu błędu - użytkownik ma wiedzieć, co wolno wgrać. */
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
