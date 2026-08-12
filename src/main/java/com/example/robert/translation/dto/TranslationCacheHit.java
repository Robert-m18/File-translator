/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.robert.translation.dto;

/**
 * Gotowy wynik znaleziony w cache'u - dokładnie tyle, ile trzeba, żeby zamknąć nowe zlecenie.
 *
 * Dwa pola, a nie cała encja: TranslationJob niesie source_content i result_content, więc
 * pobranie go po to, żeby przepisać wynik, wciągnęłoby przy okazji treść źródła - drugie
 * ćwierć megabajta, z którego nic tu nie wynika.
 *
 * sourceLang jedzie razem z treścią celowo. To dostawca go wykrył przy pierwszym tłumaczeniu,
 * a treść jest bajt w bajt ta sama, więc wykryłby dokładnie to samo. Pominięcie go zostawiłoby
 * zlecenie z cache'a bez języka źródłowego i różnica byłaby widoczna dla użytkownika na liście.
 */
public record TranslationCacheHit(String resultContent, String sourceLang) {
}
