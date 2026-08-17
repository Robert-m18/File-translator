package com.example.robert;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Zamienia Instant na typ, który potrafi zbindować sterownik JDBC.
 *
 * DLACZEGO TO ISTNIEJE - JdbcTemplate wywołuje setObject() BEZ podania typu SQL, a sterownik
 * PostgreSQL-a nie umie go wywnioskować z java.time.Instant i przerywa komunikatem
 * "Can't infer the SQL type to use for an instance of java.time.Instant". Sterownik H2 ten sam
 * parametr przyjmuje bez mrugnięcia, więc na profilu -Ph2 tego nie widać wcale, a domyślny przebieg
 * pada - i to na ZAPYTANIU, które nie ma nic wspólnego z badaną logiką, przez co komunikat
 * "bad SQL grammar" prowadzi śledztwo na SQL zamiast na typ parametru.
 * Zmierzone 2026-08-09 przy migracji na timestamptz: 7 błędów na PostgreSQL-u (wtedy: w osobnym
 * jobie CI) przy 110 zielonych na H2.
 *
 * OffsetDateTime, a nie java.sql.Timestamp: to standardowe odwzorowanie JDBC 4.2 dla kolumny
 * TIMESTAMP WITH TIME ZONE i oba sterowniki je znają. Timestamp też by przeszedł, ale sterownik
 * interpretuje go w strefie domyślnej JVM-a - czyli wprowadzałby z powrotem zależność od strefy
 * dokładnie tam, gdzie właśnie ją usunęliśmy.
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
