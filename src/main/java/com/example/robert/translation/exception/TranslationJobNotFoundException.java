/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.robert.translation.exception;

/**
 * Zlecenie nie istnieje ALBO należy do kogoś innego - z zewnątrz nie do odróżnienia.
 *
 * To jest decyzja, nie skrót. Wariant "pobierz po id, porównaj właściciela, odpowiedz 403"
 * jest wygodniejszy w kodzie i zamienia API w wyrocznię: po samym statusie odpowiedzi da się
 * przeskanować, które identyfikatory istnieją w systemie, a przy okazji policzyć, ilu jest
 * użytkowników i jak intensywnie korzystają z usługi. Zapytania w TranslationJobRepository
 * biorą userId do WHERE, więc kod NIE MA jak rozróżnić tych dwóch sytuacji i nie może
 * przypadkiem ich rozróżnić w przyszłości.
 *
 * Ta sama zasada, którą moduł auth stosuje do adresów email (jednakowa odpowiedź dla
 * zarejestrowanego i nieznanego).
 */
public class TranslationJobNotFoundException extends RuntimeException {

    public TranslationJobNotFoundException() {
        super("Nie znaleziono zlecenia tłumaczenia");
    }
}
