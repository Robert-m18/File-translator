/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.filetranslator.translation.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * Otwarty obiekt: strumień treści wraz z danymi potrzebnymi do zbudowania odpowiedzi HTTP.
 *
 * Rozmiar jest tu po to, żeby dało się ustawić nagłówek długości treści. Bez niego odpowiedź
 * leci kodowaniem porcjowym i przeglądarka nie pokazuje postępu pobierania, co przy pliku
 * wielkości kilku megabajtów jest różnicą między widocznym postępem a wrażeniem zawieszenia.
 *
 * Typ jest zamykalny, ponieważ strumień z magazynu zgodnego z S3 trzyma połączenie HTTP z puli
 * klienta. Niezamknięty wyczerpuje ją po kilkudziesięciu pobraniach, a kolejne blokują się bez
 * błędu - awaria wygląda wtedy na spowolnienie aplikacji, a nie na wyciek zasobu.
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
