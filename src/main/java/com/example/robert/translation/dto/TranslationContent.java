/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.robert.translation.dto;

import com.example.robert.translation.model.FileType;
import com.example.robert.translation.model.TargetLanguage;
import com.example.robert.translation.storage.StoredObject;

/**
 * Otwarty wynik gotowy do odesłania klientowi.
 *
 * Niesie STRUMIEŃ, nie bajty: przy pliku na kilka megabajtów wczytanie całości na stertę
 * tylko po to, żeby ją zaraz zapisać do gniazda, mnoży zużycie pamięci przez liczbę
 * równoczesnych pobrań. Wołający MUSI zamknąć object() - robi to za nas Spring, gdy
 * strumień pojedzie jako InputStreamResource.
 *
 * originalFilename i targetLang jadą razem, bo z nich powstaje nazwa pliku w nagłówku
 * Content-Disposition. Budowanie tej nazwy zostaje w kontrolerze - to prezentacja, nie
 * dziedzina - a serwis oddaje same składniki.
 */
public record TranslationContent(StoredObject object,
                                 String originalFilename,
                                 TargetLanguage targetLang,
                                 FileType fileType) {
}
