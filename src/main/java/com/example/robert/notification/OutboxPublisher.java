/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.robert.notification;

import com.example.robert.common.time.DbClock;
import com.example.robert.notification.model.OutboxMessage;
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
 * Wysyła maile zamówione w skrzynce nadawczej.
 *
 * Pętla jednego cyklu:
 *   1. rezerwacja paczki: odczyt kandydatów (status NEW, nextRetryAt <= teraz) blokujący
 *      wiersze dla tej instancji, i od razu UPDATE odsuwający nextRetryAt - wszystko
 *      w jednej krótkiej transakcji,
 *   2. wysyłka zarezerwowanych maili RÓWNOLEGLE (synchronicznie w obrębie wątku, żeby
 *      znać wynik każdego z osobna),
 *   3. zapis wyniku każdej wiadomości: SENT, albo ponowienie z backoffem, albo FAILED
 *      po wyczerpaniu prób.
 *
 * DLACZEGO WYSYŁKA JEST RÓWNOLEGŁA
 *
 * Rozmowa z SMTP trwa rzędy wielkości dłużej niż cokolwiek innego w tym cyklu. Wysyłane
 * po kolei, maile z jednej paczki czekały jeden na drugiego, więc ostatni adresat w paczce
 * dostawał link tym później, im więcej osób rejestrowało się przed nim. Kolejność wysyłki
 * nie niesie tu żadnego znaczenia - to niezależne wiadomości do niezależnych osób.
 *
 * Równoległość jest OGRANICZONA (app.outbox.concurrency), bo serwery SMTP limitują liczbę
 * jednoczesnych połączeń z jednego nadawcy. Nieograniczona pula zamieniłaby szczyt ruchu
 * w odrzucanie maili, czyli w gorszy problem niż ten, który rozwiązujemy.
 *
 * publishBatch() czeka na całą paczkę i dopiero wtedy wraca. Dzięki temu pozostaje
 * wywoływalna wprost z testów: po jej powrocie wszystkie statusy są już zapisane i nie ma
 * czego "chwilę odczekać".
 *
 * DLACZEGO KAŻDY KROK MA WŁASNĄ TRANSAKCJĘ
 *
 * Rezerwacja musi zostać ZATWIERDZONA przed wysyłką, inaczej druga instancja jej nie widzi
 * i wysyła ten sam mail równolegle. Zapis wyniku musi być osobno, bo dzieje się już po
 * operacji zewnętrznej. Jedna transakcja na cały cykl oznaczałaby trzymanie połączenia
 * z bazą przez czas rozmowy z serwerem SMTP - a to najgorsze miejsce, żeby blokować
 * połączenie z puli. Przy wysyłce równoległej ma to dodatkową wagę: tyle równoczesnych
 * wysyłek trzymałoby tyle połączeń naraz.
 *
 * TransactionTemplate, a nie @Transactional na metodach tej klasy: wywołanie własnej metody
 * omija proxy Springa, więc adnotacja nie zadziałałaby wcale. Ta sama pułapka i to samo
 * rozwiązanie co w RefreshTokenService. Przy wysyłce równoległej to zresztą jedyna opcja -
 * @Transactional nie przechodzi na wątek roboczy, a TransactionTemplate wykonuje się już
 * na tym wątku, który go woła.
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

        // Pula stała, nie wątki wirtualne: chodzi właśnie o SUFIT liczby równoczesnych
        // połączeń SMTP, a wątki wirtualne żadnego sufitu nie dają. Nazwane, żeby w logach
        // było widać, że to wysyłka, a nie wątek harmonogramu.
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
     * Jeden cykl wysyłki. Publiczna i niezależna od harmonogramu, żeby testy mogły ją
     * wywołać wprost, bez czekania i bez zgadywania, co zdążyło się wykonać w tle.
     *
     * Wraca dopiero, gdy każda wiadomość z paczki ma zapisany wynik.
     *
     * @return liczba wiadomości wysłanych w tym cyklu
     */
    public int publishBatch() {
        List<OutboxMessage> claimed = claimBatch();

        if (claimed.isEmpty()) {
            return 0;
        }
        // Jedna wiadomość nie ma z czym się zrównoleglać - przeskok na inny wątek
        // byłby tu czystym kosztem. To zresztą najczęstszy przypadek przy ruchu,
        // jaki ta aplikacja widzi.
        if (claimed.size() == 1) {
            return send(claimed.get(0)) ? 1 : 0;
        }

        List<CompletableFuture<Boolean>> results = claimed.stream()
                .map(message -> CompletableFuture.supplyAsync(() -> send(message), senders))
                .toList();

        // join() na każdym z osobna, bez allOf(): i tak czekamy na wszystkie, a tak nie
        // trzeba tablicować futures tylko po to, żeby zaraz je odpytać.
        return (int) results.stream().filter(CompletableFuture::join).count();
    }

    /**
     * Odczytuje i rezerwuje paczkę w jednej transakcji. Blokada z findReadyToSend
     * (FOR UPDATE SKIP LOCKED) trzyma się do commitu, więc żadna inna instancja nie
     * odczyta w tym czasie tych samych wierszy.
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
     * Wysyła jedną zarezerwowaną wiadomość i zapisuje wynik. Nigdy nie rzuca - leci
     * na wątku roboczym, gdzie wyjątek nie ma dokąd polecieć, a jedna zepsuta wiadomość
     * nie może przerwać wysyłki pozostałych.
     */
    private boolean send(OutboxMessage message) {
        int attempt = message.getAttempts() + 1; // claim już podbił licznik w bazie

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
            case TRANSLATION_DONE -> emailService.sendTranslationDoneEmail(
                    message.getRecipient(), vars.get("name"), vars.get("filename"));
        }
    }

    private void recordFailure(OutboxMessage message, int attempt, Exception cause) {
        String error = trim(cause.getClass().getSimpleName() + ": " + cause.getMessage());

        try {
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
            // Obcięcie jak wszędzie w tej ścieżce: ten znacznik też jest potem porównywany
            // warunkiem "<= now", więc zaokrąglenie w górę odsuwałoby ponowienie o mikrosekundę
            // i - przy dostatecznie krótkim backoffie - powtarzało ten sam wyścig.
            Instant nextRetry = DbClock.truncate(Instant.now().plus(delay));

            shortTransaction.execute(status -> repository.markRetry(message.getId(), nextRetry, error));
            log.warn("Nie udało się wysłać maila (id={}, podejście={}/{}), ponowienie za {}: {}",
                    message.getId(), attempt, properties.maxAttempts(), delay, error);

        } catch (Exception e) {
            // Padł zapis wyniku, a nie sama wysyłka. Wiersz zostaje z rezerwacją, więc
            // wróci sam po jej upływie - to jest dokładnie ten scenariusz, dla którego
            // rezerwacja jest oknem czasowym, a nie statusem.
            log.error("Nie udało się zapisać wyniku wysyłki (id={}): {}", message.getId(), e.toString());
        }
    }

    /** Kolumna last_error ma 500 znaków - dłuższy komunikat wywaliłby zapis wyniku. */
    private String trim(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 500 ? value : value.substring(0, 500);
    }

    /**
     * Przy zamykaniu aplikacji dajemy trwającym wysyłkom dokończyć. Ubicie ich w locie
     * oznaczałoby maile wysłane bez zapisanego statusu, czyli powtórki po restarcie -
     * dopuszczalne, ale niepotrzebne, skoro wystarczy chwilę poczekać.
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
