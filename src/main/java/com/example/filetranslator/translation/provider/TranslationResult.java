/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.filetranslator.translation.provider;

/**
 * Odpowiedź dostawcy tłumaczenia.
 *
 * @param translatedText         przetłumaczona treść
 * @param detectedSourceLanguage język, który dostawca rozpoznał w źródle; trafia do kolumny
 *                               source_lang (VARCHAR(10)), więc implementacja nie może tu
 *                               zwrócić dowolnie długiego tekstu
 */
public record TranslationResult(String translatedText, String detectedSourceLanguage) {
}
