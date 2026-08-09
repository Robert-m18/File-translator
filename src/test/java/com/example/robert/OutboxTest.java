package com.example.robert;

import com.example.robert.auth.AuthService;
import com.example.robert.notification.OutboxMessageRepository;
import com.example.robert.notification.OutboxPublisher;
import com.example.robert.notification.model.MailTemplate;
import com.example.robert.notification.model.OutboxMessage;
import com.example.robert.user.UserRepository;
import com.example.robert.user.dto.UserRequestDTO;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.example.robert.TestTime.sql;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * Skrzynka nadawcza maili (transactional outbox).
 *
 * Najważniejszy test to rollback_shouldLeaveNoMailOrder - dowodzi, że zamówienie maila
 * i operacja, która je zleca, są nierozdzielne. To jest cały powód istnienia tego wzorca:
 * poprzednio zamiar wysyłki żył wyłącznie w pamięci procesu, więc awaria SMTP albo restart
 * kasowały go bez śladu.
 *
 * JavaMailSender jest podmieniony na mocka - inaczej testy zależałyby od działającego
 * serwera SMTP, a chcemy tu sterować właśnie tym, czy wysyłka się udaje.
 */
@SpringBootTest
@ActiveProfiles("test")
class OutboxTest {

    private static final UserRequestDTO DTO =
            new UserRequestDTO("Adrian", "outbox@example.com", "Haslo12345");

    @MockitoBean
    private JavaMailSender mailSender;

    @Autowired
    private AuthService authService;

    @Autowired
    private OutboxPublisher publisher;

    @Autowired
    private OutboxMessageRepository outboxRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        outboxRepository.deleteAll();
        userRepository.deleteAll();
        // MimeMessageHelper potrzebuje prawdziwego obiektu wiadomości. Nowy przy każdym
        // wywołaniu, nie jeden współdzielony: paczka wysyłana jest równolegle, więc kilka
        // wątków ustawiałoby pola tej samej wiadomości naraz.
        when(mailSender.createMimeMessage())
                .thenAnswer(invocation -> new MimeMessage((jakarta.mail.Session) null));
    }

    /**
     * Cofa nextRetryAt, żeby wiersz był znów gotowy do wzięcia. Bez tego kolejne wywołanie
     * publishBatch() musiałoby czekać na upłynięcie backoffu.
     */
    private void makeReady() {
        jdbcTemplate.update("update outbox_messages set next_retry_at = ? where status = 'NEW'",
                sql(Instant.now().minusSeconds(1)));
    }

    private OutboxMessage only() {
        assertThat(outboxRepository.count()).isEqualTo(1);
        return outboxRepository.findAll().get(0);
    }

    @Test
    @DisplayName("Rejestracja zamawia mail, ale go nie wysyła")
    void register_shouldOnlyEnqueue() {
        authService.register(DTO);

        OutboxMessage message = only();
        assertThat(message.getStatus()).isEqualTo(OutboxMessage.Status.NEW);
        assertThat(message.getTemplate()).isEqualTo(MailTemplate.VERIFICATION);
        assertThat(message.getRecipient()).isEqualTo(DTO.email());
        assertThat(message.getAttempts()).isZero();
        // Surowy token jest w payloadzie, bo tylko on trafia do maila; w bazie zgłoszenia
        // leży jego SHA-256
        assertThat(message.getPayload()).contains("token");
    }

    @Test
    @DisplayName("Wycofana transakcja nie zostawia zamówienia maila")
    void rollback_shouldLeaveNoMailOrder() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        tx.execute(status -> {
            authService.register(DTO);
            // Symulujemy dowolną awarię po zapisie: deadlock, błąd walidacji wyżej, cokolwiek
            status.setRollbackOnly();
            return null;
        });

        // Sedno wzorca: albo commituje się zgłoszenie RAZEM z zamówieniem maila, albo nic.
        // Nie ma stanu "mail wysłany do rejestracji, która nie powstała".
        assertThat(outboxRepository.count()).isZero();
    }

    @Test
    @DisplayName("Publisher wysyła i oznacza wiadomość jako wysłaną")
    void publish_shouldMarkSent() {
        authService.register(DTO);

        assertThat(publisher.publishBatch()).isEqualTo(1);

        OutboxMessage message = only();
        assertThat(message.getStatus()).isEqualTo(OutboxMessage.Status.SENT);
        assertThat(message.getSentAt()).isNotNull();
        assertThat(message.getLastError()).isNull();
    }

    @Test
    @DisplayName("Maile z jednej paczki lecą równolegle, nie jeden po drugim")
    void publish_shouldSendBatchInParallel() {
        int count = 4; // tyle, ile wynosi app.outbox.concurrency w profilu testowym

        // Każda wysyłka melduje się i czeka, aż zamelduje się komplet. Przy wysyłce
        // szeregowej pierwsza nigdy by się nie doczekała pozostałych - i to jest cały
        // dowód. Bariera z limitem czasu, żeby regresja kończyła się porażką testu,
        // a nie zawieszeniem budowania.
        CountDownLatch allStarted = new CountDownLatch(count);
        doAnswer(invocation -> {
            allStarted.countDown();
            if (!allStarted.await(5, TimeUnit.SECONDS)) {
                throw new MailSendException("wysyłka szeregowa - nie doczekano się pozostałych");
            }
            return null;
        }).when(mailSender).send(any(MimeMessage.class));

        for (int i = 0; i < count; i++) {
            authService.register(new UserRequestDTO(
                    "Adrian", "rownolegle" + i + "@example.com", "Haslo12345"));
        }

        assertThat(publisher.publishBatch()).isEqualTo(count);
        assertThat(outboxRepository.findAll())
                .allMatch(message -> message.getStatus() == OutboxMessage.Status.SENT);
    }

    @Test
    @DisplayName("Wysłana wiadomość nie jest brana ponownie")
    void publish_shouldNotResendSentMessage() {
        authService.register(DTO);
        publisher.publishBatch();

        makeReady(); // nic nie zmieni - wiersz nie ma już statusu NEW
        assertThat(publisher.publishBatch()).isZero();
    }

    @Test
    @DisplayName("Awaria SMTP nie gubi maila - zostaje do ponowienia z powodem porażki")
    void failedSend_shouldStayForRetry() {
        doThrow(new MailSendException("SMTP nieosiągalny"))
                .when(mailSender).send(any(MimeMessage.class));

        authService.register(DTO);
        assertThat(publisher.publishBatch()).isZero();

        OutboxMessage message = only();
        // To jest różnica wobec poprzedniego stanu: wcześniej wyjątek był łapany
        // i logowany, a mail przepadał bezpowrotnie.
        assertThat(message.getStatus()).isEqualTo(OutboxMessage.Status.NEW);
        assertThat(message.getAttempts()).isEqualTo(1);
        assertThat(message.getLastError()).contains("SMTP nieosiągalny");
        // Backoff: kolejna próba dopiero po odstępie, nie natychmiast
        assertThat(message.getNextRetryAt()).isAfter(Instant.now());
    }

    @Test
    @DisplayName("Wiadomość w oknie backoffu nie jest brana przed czasem")
    void messageInBackoffWindow_shouldBeSkipped() {
        doThrow(new MailSendException("SMTP nieosiągalny"))
                .when(mailSender).send(any(MimeMessage.class));

        authService.register(DTO);
        publisher.publishBatch();

        // Bez cofnięcia nextRetryAt wiersz jest niewidoczny dla publishera
        assertThat(publisher.publishBatch()).isZero();
        assertThat(only().getAttempts()).isEqualTo(1);
    }

    @Test
    @DisplayName("Po wyczerpaniu prób wiadomość dostaje status FAILED")
    void exhaustedAttempts_shouldEndAsFailed() {
        doThrow(new MailSendException("SMTP nieosiągalny"))
                .when(mailSender).send(any(MimeMessage.class));

        authService.register(DTO);

        // Profil testowy: max-attempts = 3
        for (int i = 0; i < 3; i++) {
            makeReady();
            publisher.publishBatch();
        }

        OutboxMessage message = only();
        assertThat(message.getStatus()).isEqualTo(OutboxMessage.Status.FAILED);
        assertThat(message.getAttempts()).isEqualTo(3);
        // Dopiero to daje odpowiedź na pytanie "czy maile w ogóle wychodzą" -
        // wcześniej istniała wyłącznie w logach
        assertThat(outboxRepository.countFailed()).isEqualTo(1);

        // Wiadomość FAILED nie wraca do obiegu sama
        makeReady();
        assertThat(publisher.publishBatch()).isZero();
    }

    @Test
    @DisplayName("Rejestracja na zajęty adres zamawia powiadomienie, nie link potwierdzający")
    void registerOnTakenEmail_shouldEnqueueAccountExistsMail() {
        authService.register(DTO);
        publisher.publishBatch();
        authService.confirmEmail(rawTokenFromPayload());
        outboxRepository.deleteAll();

        authService.register(new UserRequestDTO("Podszywacz", DTO.email(), "InneHaslo9"));

        assertThat(only().getTemplate()).isEqualTo(MailTemplate.ACCOUNT_EXISTS);
    }

    /** Wyciąga surowy token z payloadu zamówienia, żeby móc "kliknąć w link". */
    private String rawTokenFromPayload() {
        String payload = outboxRepository.findAll().get(0).getPayload();
        int start = payload.indexOf("\"token\":\"") + 9;
        return payload.substring(start, payload.indexOf('"', start));
    }
}
