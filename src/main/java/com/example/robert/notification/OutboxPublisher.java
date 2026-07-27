/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.robert.notification;

import com.example.robert.notification.model.OutboxMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Wysyła maile zamówione w skrzynce nadawczej.
 *
 * Pętla jednego cyklu:
 *   1. odczyt kandydatów (status NEW, nextRetryAt <= teraz),
 *   2. rezerwacja wiersza atomowym UPDATE-em - kto dostanie 1, ten wysyła,
 *   3. wysyłka (synchronicznie, żeby znać wynik),
 *   4. zapis wyniku: SENT, albo ponowienie z backoffem, albo FAILED po wyczerpaniu prób.
 *
 * DLACZEGO KAŻDY KROK MA WŁASNĄ TRANSAKCJĘ
 *
 * Rezerwacja musi zostać ZATWIERDZONA przed wysyłką, inaczej druga instancja jej nie widzi
 * i wysyła ten sam mail równolegle. Zapis wyniku musi być osobno, bo dzieje się już po
 * operacji zewnętrznej. Jedna transakcja na cały cykl oznaczałaby trzymanie połączenia
 * z bazą przez czas rozmowy z serwerem SMTP - a to najgorsze miejsce, żeby blokować
 * połączenie z puli.
 *
 * TransactionTemplate, a nie @Transactional na metodach tej klasy: wywołanie własnej metody
 * omija proxy Springa, więc adnotacja nie zadziałałaby wcale. Ta sama pułapka i to samo
 * rozwiązanie co w RefreshTokenService.
 *
 * GWARANCJA DOSTAWY
 *
 * At-least-once. Jeśli proces padnie między udaną wysyłką a zapisem statusu, wiersz wróci
 * po upływie rezerwacji i mail poleci drugi raz. Nie da się tego wyeliminować bez
 * transakcji rozproszonej obejmującej SMTP, czyli w praktyce nie da się. Przy tych mailach
 * duplikat jest nieszkodliwy: link jest ten sam, a jednorazowość tokenu i tak pilnuje baza.
 */
@Slf4j
@Component
public class OutboxPublisher {

    private static final TypeReference<Map<String, String>> PAYLOAD_TYPE = new TypeReference<>() {};

    private final OutboxMessageRepository repository;
    private final EmailService emailService;
    private final ObjectMapper objectMapper;
    private final OutboxProperties properties;
    private final TransactionTemplate shortTransaction;

    public OutboxPublisher(OutboxMessageRepository repository,
                           EmailService emailService,
                           ObjectMapper objectMapper,
                           OutboxProperties properties,
                           PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.emailService = emailService;
        this.objectMapper = objectMapper;
        this.properties = properties;

        this.shortTransaction = new TransactionTemplate(transactionManager);
        this.shortTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Scheduled(fixedDelayString = "${app.outbox.poll-interval:PT10S}")
    public void publishScheduled() {
        if (!properties.enabled()) {
            return;
        }
        publishBatch();
    }

    /**
     * Jeden cykl wysyłki. Publiczna i niezależna od harmonogramu, żeby testy mogły ją
     * wywołać wprost, bez czekania i bez zgadywania, co zdążyło się wykonać w tle.
     *
     * @return liczba wiadomości wysłanych w tym cyklu
     */
    public int publishBatch() {
        LocalDateTime now = LocalDateTime.now();

        List<OutboxMessage> candidates = shortTransaction.execute(status ->
                repository.findReadyToSend(now, Limit.of(properties.batchSize())));

        if (candidates == null || candidates.isEmpty()) {
            return 0;
        }

        int sent = 0;
        for (OutboxMessage message : candidates) {
            if (send(message, now)) {
                sent++;
            }
        }
        return sent;
    }

    private boolean send(OutboxMessage message, LocalDateTime now) {
        // Rezerwacja: przesuwa nextRetryAt w przyszłość i podbija licznik podejść.
        // Zwrot 0 znaczy, że inna instancja była pierwsza - wtedy po prostu odpuszczamy.
        Integer claimed = shortTransaction.execute(status -> repository.claim(
                message.getId(), now, now.plus(properties.claimTimeout())));

        if (claimed == null || claimed == 0) {
            return false;
        }

        int attempt = message.getAttempts() + 1; // claim już podbił licznik w bazie

        try {
            deliver(message);
            shortTransaction.execute(status -> repository.markSent(message.getId(), LocalDateTime.now()));
            log.info("Mail wysłany ze skrzynki nadawczej (id={}, szablon={}, podejście={})",
                    message.getId(), message.getTemplate(), attempt);
            return true;

        } catch (Exception e) {
            recordFailure(message, attempt, e);
            return false;
        }
    }

    private void deliver(OutboxMessage message) {
        Map<String, String> vars = objectMapper.readValue(message.getPayload(), PAYLOAD_TYPE);

        // switch bez gałęzi default: dołożenie wartości do MailTemplate przestanie się
        // kompilować, dopóki nie dopiszemy tu obsługi. O to właśnie chodziło w enumie.
        switch (message.getTemplate()) {
            case VERIFICATION -> emailService.sendVerificationEmail(
                    message.getRecipient(), vars.get("name"), vars.get("token"));
            case PASSWORD_RESET -> emailService.sendPasswordResetEmail(
                    message.getRecipient(), vars.get("name"), vars.get("token"));
            case ACCOUNT_EXISTS -> emailService.sendAccountExistsEmail(message.getRecipient());
        }
    }

    private void recordFailure(OutboxMessage message, int attempt, Exception cause) {
        String error = trim(cause.getClass().getSimpleName() + ": " + cause.getMessage());

        if (attempt >= properties.maxAttempts()) {
            shortTransaction.execute(status -> repository.markFailed(message.getId(), error));
            // ERROR, nie WARN: to jest stan wymagający człowieka. Liczbę takich wiadomości
            // daje OutboxMessageRepository.countFailed() - i to jest odpowiedź na pytanie
            // "czy maile w ogóle wychodzą", której wcześniej nie było jak uzyskać.
            log.error("Mail porzucony po {} podejściach (id={}, szablon={}): {}",
                    attempt, message.getId(), message.getTemplate(), error);
            return;
        }

        // Backoff wykładniczy - chwilowa awaria SMTP nie zamienia się w dobijanie serwera
        Duration delay = properties.retryBackoff().multipliedBy(1L << (attempt - 1));
        LocalDateTime nextRetry = LocalDateTime.now().plus(delay);

        shortTransaction.execute(status -> repository.markRetry(message.getId(), nextRetry, error));
        log.warn("Nie udało się wysłać maila (id={}, podejście={}/{}), ponowienie za {}: {}",
                message.getId(), attempt, properties.maxAttempts(), delay, error);
    }

    /** Kolumna last_error ma 500 znaków - dłuższy komunikat wywaliłby zapis wyniku. */
    private String trim(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 500 ? value : value.substring(0, 500);
    }
}
