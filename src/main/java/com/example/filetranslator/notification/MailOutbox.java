/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.filetranslator.notification;

import com.example.filetranslator.common.time.DbClock;
import com.example.filetranslator.notification.model.MailTemplate;
import com.example.filetranslator.notification.model.OutboxMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Map;

/**
 * Zamawianie maili. Jedyne wejście do skrzynki nadawczej dla reszty aplikacji.
 *
 * Metody tej klasy nie wysyłają maila, tylko zapisują zamówienie, i trzeba je wołać wewnątrz
 * transakcji operacji, która mail zamawia. Zamiar wysyłki commituje się wtedy razem z nią albo
 * wcale, więc wycofana rejestracja nie zostawia zamówienia i nie da się wysłać linku do konta,
 * które nie powstało.
 *
 * Zapis zamówienia w bazie, zamiast wysyłki po zatwierdzeniu transakcji, jest tym, co pozwala
 * przetrwać awarii serwera pocztowego i restartowi aplikacji: zamiar wysyłki nie żyje w pamięci
 * procesu, tylko w tabeli, z której podejmie go najbliższy cykl wysyłkowy.
 *
 * Klasa należy do pakietu powiadomień, a wołana jest z obszaru uwierzytelniania. Kierunek
 * zależności jest zamierzony: obszar wywołujący wie, że chce wysłać wiadomość, a pakiet
 * powiadomień nie wie nic o rejestracji ani o resetach haseł.
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

    /**
     * Powiadomienie o gotowym tłumaczeniu.
     *
     * Payload niesie NAZWĘ pliku, nigdy jego treści ani treści tłumaczenia: kolumna payload
     * leży w bazie plaintekstem, więc wszystko, co tu wpiszemy, przestaje być chronione
     * retencją tabeli zleceń i staje się kopią danych użytkownika w drugim miejscu.
     */
    public void enqueueTranslationDone(String email, String name, String filename) {
        enqueue(email, MailTemplate.TRANSLATION_DONE, Map.of("name", name, "filename", filename));
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
                // najbliższego cyklu publishera. Uzasadnienie: DbClock.
                DbClock.now()
        ));

        // Bez adresu w logu - to dana osobowa; powiązanie z żądaniem daje jego identyfikator.
        // Identyfikator wiadomości jest tu po to, żeby dało się przejść od żądania do wysyłki:
        // wysyłka pracuje na wątkach harmonogramu i puli wysyłkowej, gdzie identyfikator żądania
        // nie istnieje, więc bez wspólnego identyfikatora wiadomości łańcuch diagnostyczny
        // urywałby się w tym miejscu.
        log.info("Mail zamówiony w skrzynce nadawczej (id={}, szablon={})", saved.getId(), template);
    }
}
