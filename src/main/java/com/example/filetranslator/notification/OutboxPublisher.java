/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.filetranslator.notification;

import com.example.filetranslator.common.time.DbClock;
import com.example.filetranslator.notification.model.OutboxMessage;
import jakarta.annotation.PreDestroy;
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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Wysyła maile zamówione w skrzynce nadawczej (tabela outbox_messages).
 *
 * Cykl składa się z trzech kroków: rezerwacji paczki wiadomości w jednej krótkiej transakcji,
 * równoległej wysyłki zarezerwowanych wiadomości i zapisu wyniku każdej z nich osobno - wysłana,
 * ponowienie z backoffem albo porzucenie po wyczerpaniu prób.
 *
 * Wysyłka jest równoległa, ponieważ rozmowa z serwerem pocztowym trwa rzędy wielkości dłużej niż
 * cokolwiek innego w tym cyklu. Przy wysyłce po kolei maile z jednej paczki czekają jeden na
 * drugiego, więc ostatni adresat dostaje link tym później, im więcej osób rejestrowało się przed
 * nim; kolejność wysyłki nie niesie tu żadnego znaczenia, bo są to niezależne wiadomości do
 * niezależnych osób. Równoległość jest ograniczona konfiguracją, bo serwery pocztowe limitują
 * liczbę jednoczesnych połączeń jednego nadawcy - pula bez ograniczenia zamieniłaby szczyt ruchu
 * w odrzucanie maili.
 *
 * Każdy krok ma własną transakcję. Rezerwacja musi zostać zatwierdzona przed wysyłką, inaczej
 * druga instancja jej nie zobaczy i wyśle ten sam mail równolegle. Zapis wyniku następuje po
 * operacji zewnętrznej, więc również musi być osobny. Jedna transakcja na cały cykl oznaczałaby
 * trzymanie połączenia z bazą przez czas rozmowy z serwerem pocztowym, a przy wysyłce równoległej
 * tylu połączeń naraz, ile wiadomości jest w locie.
 *
 * Transakcje prowadzone są programowo, a nie adnotacją: wywołanie własnej metody omija proxy
 * Springa, a przy wysyłce równoległej adnotacja i tak nie przeniosłaby się na wątek roboczy.
 *
 * Gwarancja dostawy to at-least-once. Awaria między udaną wysyłką a zapisem statusu powoduje
 * powtórzenie wiadomości po upływie rezerwacji. Wyeliminowanie tego wymagałoby transakcji
 * rozproszonej obejmującej serwer pocztowy, a przy tych wiadomościach duplikat jest nieszkodliwy:
 * link jest ten sam, a jednorazowości tokenu pilnuje baza.
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
    private final ExecutorService senders;

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

        // Pula stała, a nie wątki wirtualne: celem jest sufit liczby równoczesnych połączeń
        // z serwerem pocztowym, którego wątki wirtualne nie dają. Nazwane wątki pozwalają odróżnić
        // w logach wysyłkę od zadań harmonogramu.
        AtomicInteger counter = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "outbox-sender-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        this.senders = Executors.newFixedThreadPool(properties.concurrency(), factory);
    }

    @Scheduled(fixedDelayString = "${app.outbox.poll-interval:PT10S}")
    public void publishScheduled() {
        if (!properties.enabled()) {
            return;
        }
        publishBatch();
    }

    /**
     * Wykonuje jeden cykl wysyłki i wraca dopiero wtedy, gdy każda wiadomość z paczki ma zapisany
     * wynik. Metoda jest publiczna i niezależna od harmonogramu, dzięki czemu testy wywołują ją
     * wprost, bez czekania i bez zgadywania, co zdążyło wykonać się w tle.
     *
     * @return liczba wiadomości wysłanych w tym cyklu
     */
    public int publishBatch() {
        List<OutboxMessage> claimed = claimBatch();

        if (claimed.isEmpty()) {
            return 0;
        }
        // Pojedyncza wiadomość nie ma z czym się zrównoleglać, a przeskok na inny wątek kosztuje.
        // Przy typowym ruchu jest to najczęstszy przypadek.
        if (claimed.size() == 1) {
            return send(claimed.get(0)) ? 1 : 0;
        }

        List<CompletableFuture<Boolean>> results = claimed.stream()
                .map(message -> CompletableFuture.supplyAsync(() -> send(message), senders))
                .toList();

        // Oczekiwanie na każdy wynik z osobna zamiast na wspólny future: i tak potrzebne są
        // wszystkie wyniki, a tak nie trzeba budować tablicy tylko po to, żeby ją zaraz odpytać.
        return (int) results.stream().filter(CompletableFuture::join).count();
    }

    /**
     * Odczytuje i rezerwuje paczkę wiadomości w jednej transakcji.
     *
     * Blokada założona przez odczyt kandydatów trwa do commitu, więc dwie instancje aplikacji
     * pobierają rozłączne paczki zamiast konkurować o te same wiersze.
     *
     * @return wiadomości zarezerwowane dla tej instancji, w stanie sprzed rezerwacji
     */
    private List<OutboxMessage> claimBatch() {
        Instant now = DbClock.now();

        List<OutboxMessage> claimed = shortTransaction.execute(status -> {
            List<OutboxMessage> candidates =
                    repository.findReadyToSend(now, Limit.of(properties.batchSize()));

            if (candidates.isEmpty()) {
                return List.of();
            }

            repository.claim(
                    candidates.stream().map(OutboxMessage::getId).toList(),
                    now,
                    now.plus(properties.claimTimeout()));

            return candidates;
        });

        return claimed == null ? List.of() : claimed;
    }

    /**
     * Wysyła jedną zarezerwowaną wiadomość i zapisuje wynik.
     *
     * Metoda nie wypuszcza wyjątków: wykonuje się na wątku roboczym, gdzie wyjątek nie miałby
     * dokąd polecieć, a jedna zepsuta wiadomość nie może przerwać wysyłki pozostałych.
     */
    private boolean send(OutboxMessage message) {
        int attempt = message.getAttempts() + 1; // rezerwacja podbiła już licznik w bazie

        try {
            deliver(message);
            shortTransaction.execute(status -> repository.markSent(message.getId(), DbClock.now()));
            log.info("Mail wysłany ze skrzynki nadawczej (id={}, szablon={}, podejście={})",
                    message.getId(), message.getTemplate(), attempt);
            return true;

        } catch (Exception e) {
            recordFailure(message, attempt, e);
            return false;
        }
    }

    /** Odtwarza parametry wiadomości z zapisanego ładunku i wywołuje właściwy szablon. */
    private void deliver(OutboxMessage message) {
        Map<String, String> vars = objectMapper.readValue(message.getPayload(), PAYLOAD_TYPE);

        // Instrukcja bez gałęzi domyślnej: dołożenie nowej wartości do typu wyliczeniowego
        // przestanie się kompilować, dopóki nie powstanie tutaj jej obsługa.
        switch (message.getTemplate()) {
            case VERIFICATION -> emailService.sendVerificationEmail(
                    message.getRecipient(), vars.get("name"), vars.get("token"));
            case PASSWORD_RESET -> emailService.sendPasswordResetEmail(
                    message.getRecipient(), vars.get("name"), vars.get("token"));
            case ACCOUNT_EXISTS -> emailService.sendAccountExistsEmail(message.getRecipient());
            case TRANSLATION_DONE -> emailService.sendTranslationDoneEmail(
                    message.getRecipient(), vars.get("name"), vars.get("filename"));
        }
    }

    /** Zapisuje nieudaną próbę: ponowienie z backoffem albo porzucenie po wyczerpaniu podejść. */
    private void recordFailure(OutboxMessage message, int attempt, Exception cause) {
        String error = trim(cause.getClass().getSimpleName() + ": " + cause.getMessage());

        try {
            if (attempt >= properties.maxAttempts()) {
                shortTransaction.execute(status -> repository.markFailed(message.getId(), error));
                // Poziom ERROR, nie WARN: jest to stan wymagający reakcji człowieka. Liczbę takich
                // wiadomości podaje osobne zapytanie repozytorium i to ona odpowiada na pytanie,
                // czy poczta w ogóle wychodzi.
                log.error("Mail porzucony po {} podejściach (id={}, szablon={}): {}",
                        attempt, message.getId(), message.getTemplate(), error);
                return;
            }

            // Backoff wykładniczy sprawia, że chwilowa awaria serwera pocztowego nie zamienia się
            // w dobijanie go kolejnymi próbami.
            Duration delay = properties.retryBackoff().multipliedBy(1L << (attempt - 1));
            // Obcięcie znacznika do precyzji kolumny: wartość jest potem porównywana warunkiem
            // "<= now", więc zaokrąglenie w górę odsuwałoby ponowienie i przy krótkim backoffie
            // powtarzało ten sam wyścig.
            Instant nextRetry = DbClock.truncate(Instant.now().plus(delay));

            shortTransaction.execute(status -> repository.markRetry(message.getId(), nextRetry, error));
            log.warn("Nie udało się wysłać maila (id={}, podejście={}/{}), ponowienie za {}: {}",
                    message.getId(), attempt, properties.maxAttempts(), delay, error);

        } catch (Exception e) {
            // Zawiódł zapis wyniku, a nie sama wysyłka. Wiersz zachowuje rezerwację, więc wróci
            // samoczynnie po jej upływie - dokładnie ten scenariusz, dla którego rezerwacja jest
            // oknem czasowym, a nie statusem.
            log.error("Nie udało się zapisać wyniku wysyłki (id={}): {}", message.getId(), e.toString());
        }
    }

    /** Kolumna z opisem błędu mieści 500 znaków - dłuższy komunikat przerwałby zapis wyniku. */
    private String trim(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 500 ? value : value.substring(0, 500);
    }

    /**
     * Pozwala trwającym wysyłkom dokończyć się przy zamykaniu aplikacji.
     *
     * Przerwanie ich w locie oznaczałoby maile wysłane bez zapisanego statusu, czyli powtórki po
     * restarcie - dopuszczalne, ale niepotrzebne, skoro wystarczy odczekać do końca paczki.
     */
    @PreDestroy
    void shutdown() throws InterruptedException {
        senders.shutdown();
        if (!senders.awaitTermination(30, TimeUnit.SECONDS)) {
            log.warn("Wysyłka maili nie zakończyła się w 30 s - przerywam");
            senders.shutdownNow();
        }
    }
}
