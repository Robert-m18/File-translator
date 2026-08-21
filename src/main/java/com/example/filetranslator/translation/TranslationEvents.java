/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.filetranslator.translation;

import com.example.filetranslator.notification.MailOutbox;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Jedyne wyjście faktu "zlecenie zostało wykonane" poza ten pakiet.
 *
 * Metody tej klasy trzeba wywoływać wewnątrz transakcji zapisującej wynik. Zamówienie maila
 * commituje się wtedy razem z nim albo wcale, dzięki czemu wycofany zapis nie zostawia
 * użytkownikowi wiadomości o gotowym tłumaczeniu prowadzącej do zlecenia, które nadal czeka.
 *
 * Klasa jest osobna, mimo że dziś robi jedną rzecz, ponieważ stanowi szew pod publikację zdarzeń
 * na zewnątrz - na przykład do brokera albo do analityki. Wtedy dochodzi tutaj druga linia, a
 * wykonawca i serwis pozostają nietknięte. Szew nie jest pusty: ma dzisiejsze, realne użycie.
 *
 * Zdarzenia Springa nie są tu użyte świadomie. Ta aplikacja miała już taki mechanizm jako drogę
 * do wysyłki maili i został usunięty, ponieważ zamiar wysyłki żył wyłącznie w pamięci procesu, więc
 * awaria serwera pocztowego albo restart między commitem a wysyłką kasowały go bez śladu.
 * Skrzynka nadawcza rozwiązuje dokładnie ten problem.
 *
 * Nie ma powiadomienia o niepowodzeniu, ponieważ porażki są skorelowane: awaria dostawcy wywraca
 * wszystkie zlecenia naraz, więc mail o niepowodzeniu zamieniłby jedną awarię w lawinę wiadomości
 * do wszystkich użytkowników, i to w momencie, w którym poczta jest potrzebna do rejestracji
 * i resetów haseł. Status nieudanego zlecenia widać na liście i w odpowiedzi API.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TranslationEvents {

    private final MailOutbox mailOutbox;

    public void completed(TranslationCompletedEvent event) {
        mailOutbox.enqueueTranslationDone(
                event.recipientEmail(),
                event.recipientName(),
                event.originalFilename());

        // Bez adresu i bez nazwy pliku - to dane użytkownika. Identyfikator zlecenia jest tu
        // jedynym uchwytem diagnostycznym, bo wykonawca pracuje na wątku bez kontekstu żądania,
        // więc identyfikator żądania nie istnieje.
        log.debug("Zamówiono powiadomienie o gotowym tłumaczeniu (id={})", event.jobId());
    }
}
