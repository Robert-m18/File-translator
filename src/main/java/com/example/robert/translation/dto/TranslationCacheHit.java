/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.robert.translation.dto;

/**
 * Wskazanie na gotowy wynik znaleziony w cache'u - tyle, ile trzeba, żeby zamknąć nowe zlecenie.
 *
 * KLUCZ, nie treść, i to jest tu więcej niż oszczędność pamięci: mając klucz, trafienie
 * realizuje się jednym serwerowym CopyObject, więc bajty w ogóle nie przechodzą przez
 * aplikację. Wersja z treścią musiałaby je pobrać i odesłać z powrotem do magazynu.
 *
 * Kopiujemy, a nie wskazujemy dwoma zleceniami na jeden obiekt: wyłączność zlecenia na swój
 * prefiks jest tym, co pozwala kasować je jednym wywołaniem, bez liczenia referencji.
 *
 * sourceLang jedzie razem celowo. To dostawca go wykrył przy pierwszym tłumaczeniu, a treść
 * jest bajt w bajt ta sama, więc wykryłby dokładnie to samo. Pominięcie go zostawiłoby
 * zlecenie z cache'a bez języka źródłowego i różnica byłaby widoczna dla użytkownika na liście.
 *
 * charCount przenosi się razem z wynikiem, bo dla DOKUMENTU nie da się go policzyć u nas -
 * liczbę znaków podaje dostawca dopiero po przetłumaczeniu. Zlecenie zaspokojone z cache'a
 * nigdy nie trafia do dostawcy, więc bez przepisania tej wartości zostałoby z zerem
 * i użytkownik widziałby "0 znaków" przy gotowym tłumaczeniu.
 */
public record TranslationCacheHit(String resultObjectKey, String sourceLang, int charCount) {
}
