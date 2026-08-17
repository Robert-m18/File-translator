/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.filetranslator.translation.model;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Języki docelowe, na które wolno zlecić tłumaczenie.
 *
 * DLACZEGO ENUM, A NIE GOŁY TEKST PODANY DOSTAWCY: przetwarzanie jest asynchroniczne, więc
 * błąd wykryty dopiero przez workera wraca do użytkownika kilka sekund PO tym, jak dostał
 * 202 - i to jako zlecenie ze statusem FAILED i komunikatem od obcego API, zamiast jako
 * czytelne 400 przy wysyłce. Asynchroniczność wymaga OSTRZEJSZEJ walidacji na brzegu, nie
 * luźniejszej: wszystko, co da się odrzucić od razu, ma zostać odrzucone od razu.
 *
 * Drugi powód jest praktyczny: kod języka trafia do żądania HTTP do dostawcy, a enum
 * gwarantuje, że nie wstawimy tam czegokolwiek, co przyszło od klienta.
 *
 * Zestaw jest celowo mały. Rozszerzanie go to dopisanie stałej - ale każda nowa wartość
 * musi być wspierana przez dostawcę, więc nie jest to zmiana kosmetyczna.
 */
public enum TargetLanguage {

    EN_GB("EN-GB"),
    EN_US("EN-US"),
    DE("DE"),
    FR("FR"),
    ES("ES"),
    IT("IT"),
    PL("PL");

    /** Kod w formacie oczekiwanym przez API dostawcy (DeepL: "EN-GB", nie "EN_GB"). */
    private final String apiCode;

    TargetLanguage(String apiCode) {
        this.apiCode = apiCode;
    }

    public String apiCode() {
        return apiCode;
    }

    /**
     * Dozwolone wartości jako tekst - do komunikatu błędu przy nieznanym języku.
     * Bez tego użytkownik dostaje "nieprawidłowa wartość" i musi zgadywać, co wolno.
     */
    public static String allowedValues() {
        return Arrays.stream(values())
                .map(Enum::name)
                .collect(Collectors.joining(", "));
    }
}
