/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.robert.translation;

import com.example.robert.notification.MailOutbox;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Jedyne miejsce, przez które fakt "zlecenie skończone" wychodzi poza ten pakiet.
 *
 * WAŻNE dla wołających: metody tej klasy trzeba wywołać WEWNĄTRZ transakcji zapisującej
 * wynik. Zamówienie maila commituje się wtedy razem z nim albo wcale - wycofany zapis nie
 * zostawia użytkownikowi wiadomości "tłumaczenie gotowe" prowadzącej do zlecenia, które
 * dalej czeka w kolejce.
 *
 * DLACZEGO ISTNIEJE JAKO OSOBNA KLASA, skoro dziś robi jedną rzecz: to jest szew pod
 * publikację zdarzeń na zewnątrz (Kafka, analityka). Gdy się pojawi, dochodzi tu druga
 * linia, a worker i serwis zostają nietknięte. Szew jest tani, bo nie jest pusty - ma
 * dzisiejsze, realne użycie.
 *
 * DLACZEGO NIE ApplicationEventPublisher: ten projekt miał już zdarzenia Springa jako drogę
 * do maili (@TransactionalEventListener + @Async) i ŚWIADOMIE je usunął - zamiar wysyłki żył
 * wyłącznie w pamięci procesu, więc awaria SMTP albo restart między commitem a wysyłką
 * kasowały go bez śladu. Uzasadnienie jest w MailOutbox. Dokładanie tamtego mechanizmu
 * z powrotem tylko po to, żeby "było gdzie podpiąć Kafkę", dałoby hook bez konsumenta.
 *
 * DLACZEGO NIE MA POWIADOMIENIA O PORAŻCE: porażki są skorelowane. Awaria dostawcy wywraca
 * wszystkie zlecenia naraz, więc mail o niepowodzeniu zamieniłby jedną awarię w lawinę
 * wiadomości do wszystkich użytkowników - w najgorszym możliwym momencie, bo dokładnie wtedy,
 * gdy poczta i tak jest potrzebna do rejestracji i resetów haseł. Status FAILED widać
 * na liście zleceń i przez GET /translations/{id}.
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

        // Bez adresu i bez nazwy pliku - dane użytkownika. id zlecenia jest tu tym samym
        // uchwytem co id wiersza skrzynki nadawczej: worker pracuje na wątku bez MDC,
        // więc traceId nie istnieje i bez id łańcuch diagnostyczny się urywa.
        log.debug("Zamówiono powiadomienie o gotowym tłumaczeniu (id={})", event.jobId());
    }
}
