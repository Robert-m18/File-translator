/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.filetranslator.translation.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * Otwarty obiekt: strumień treści plus to, co trzeba wiedzieć, żeby zbudować odpowiedź HTTP.
 *
 * size jest tu po to, żeby dało się ustawić Content-Length. Bez niego odpowiedź leci
 * kodowaniem porcjowym i przeglądarka nie pokazuje postępu pobierania - przy pliku
 * na kilka megabajtów to różnica między "widzę, że się ściąga" a "chyba zawisło".
 *
 * AutoCloseable, bo strumień z S3 trzyma połączenie HTTP z puli klienta. Niezamknięty
 * wyczerpuje ją po kilkudziesięciu pobraniach i kolejne blokują się bez błędu - awaria
 * wyglądająca na "aplikacja zwolniła", nie na wyciek zasobu.
 */
public record StoredObject(InputStream content, long size, String contentType) implements AutoCloseable {

    @Override
    public void close() {
        try {
            content.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
