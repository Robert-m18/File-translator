/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.robert.notification;

import com.example.robert.notification.model.MailTemplate;
import com.example.robert.notification.model.OutboxMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Zamawianie maili. Jedyne wejście do skrzynki nadawczej dla reszty aplikacji.
 *
 * WAŻNE dla wołających: te metody NIE wysyłają maila, tylko zapisują zamówienie.
 * Trzeba je wołać wewnątrz transakcji operacji, która mail zamawia - wtedy zamiar wysyłki
 * commituje się razem z nią albo wcale. Wycofana rejestracja nie zostawia po sobie
 * zamówienia, więc nie da się wysłać linku do konta, które nie powstało.
 *
 * Zastąpiło to zdarzenia @TransactionalEventListener(AFTER_COMMIT). Tamten wariant też nie
 * wysyłał maili dla wycofanych transakcji, ale zamiar wysyłki żył wyłącznie w pamięci
 * procesu: awaria SMTP albo restart poda między commitem a wysyłką kasowały go bez śladu.
 *
 * Klasa siedzi w notification, a wołana jest z auth. Kierunek zależności jest zamierzony:
 * auth wie, że chce wysłać mail, a notification nie wie nic o rejestracji ani o resetach.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MailOutbox {

    private final OutboxMessageRepository repository;
    private final ObjectMapper objectMapper;

    public void enqueueVerification(String email, String name, String rawToken) {
        enqueue(email, MailTemplate.VERIFICATION, Map.of("name", name, "token", rawToken));
    }

    public void enqueuePasswordReset(String email, String name, String rawToken) {
        enqueue(email, MailTemplate.PASSWORD_RESET, Map.of("name", name, "token", rawToken));
    }

    public void enqueueAccountExists(String email) {
        // Bez zmiennych - ten mail nie niesie tokenu, bo nie ma czego aktywować
        enqueue(email, MailTemplate.ACCOUNT_EXISTS, Map.of());
    }

    private void enqueue(String recipient, MailTemplate template, Map<String, String> variables) {
        OutboxMessage saved = repository.save(new OutboxMessage(
                recipient,
                template,
                objectMapper.writeValueAsString(variables),
                // Obcięty do precyzji kolumny - inaczej baza zaokrągli go W GÓRĘ i wiersz
                // zapisany "na teraz" będzie miał czas w przyszłości, niewidoczny dla
                // najbliższego cyklu publishera. Uzasadnienie: OutboxClock.
                OutboxClock.now()
        ));

        // Bez adresu w logu - to dane osobowe. Do powiązania z konkretnym żądaniem
        // służy traceId dokładany przez TraceIdFilter.
        //
        // id jest tu po to, żeby dało się przejść od żądania do wysyłki. OutboxPublisher
        // pracuje na wątku harmonogramu i na wątkach puli wysyłkowej, gdzie MDC jest puste,
        // więc jego logi nie mają traceId - i bez id po obu stronach łańcuch urywał się
        // dokładnie w tym miejscu. Teraz: traceId -> id (tutaj), id -> wynik (tam).
        log.info("Mail zamówiony w skrzynce nadawczej (id={}, szablon={})", saved.getId(), template);
    }
}
