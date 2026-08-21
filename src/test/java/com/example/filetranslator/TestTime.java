package com.example.filetranslator;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Zamienia Instant na typ, który potrafi zbindować sterownik JDBC.
 *
 * Klasa istnieje, ponieważ szablon JDBC ustawia parametr bez podania typu SQL, a sterownik
 * bazy produkcyjnej nie potrafi wywnioskować go z typu reprezentującego punkt na osi czasu
 * i przerywa. Sterownik bazy używanej w wariancie bez Dockera przyjmuje ten sam parametr bez
 * zastrzeżeń, więc problemu tam nie widać, a domyślny przebieg pada - i to na zapytaniu, które
 * nie ma nic wspólnego z badaną logiką, przez co komunikat o błędnej składni SQL prowadzi
 * poszukiwania w stronę zapytania zamiast typu parametru.


 *
 * Konwersja idzie na typ z przesunięciem strefowym, a nie na starszy typ znacznika czasu: jest
 * to standardowe odwzorowanie kolumny czasowej ze strefą i znają je oba sterowniki. Starszy typ
 * również by przeszedł, ale sterownik interpretuje go w strefie domyślnej maszyny wirtualnej,
 * czyli wprowadzałby z powrotem zależność od strefy dokładnie tam, gdzie została usunięta.
 *
 * Dotyczy wyłącznie surowego JDBC w testach. Kod produkcyjny idzie przez Hibernate, który mapuje
 * Instant sam i tego problemu nie ma.
 */
public final class TestTime {

    private TestTime() {
    }

    public static OffsetDateTime sql(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
