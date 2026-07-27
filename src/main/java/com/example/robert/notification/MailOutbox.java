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
        repository.save(new OutboxMessage(
                recipient,
                template,
                objectMapper.writeValueAsString(variables),
                LocalDateTime.now()
        ));

        // Bez adresu w logu - to dane osobowe. Do powiązania z konkretnym żądaniem
        // służy traceId dokładany przez TraceIdFilter.
        log.info("Mail zamówiony w skrzynce nadawczej ({})", template);
    }
}
