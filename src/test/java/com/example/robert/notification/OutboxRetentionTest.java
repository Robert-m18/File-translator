package com.example.robert.notification;

import com.example.robert.notification.model.MailTemplate;
import com.example.robert.notification.model.OutboxMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Retencja skrzynki nadawczej: wysłane wiadomości znikają, reszta zostaje.
 *
 * Do tej pory nic nie kasowało wierszy z outbox_messages. Nie chodziło o rozmiar tabeli -
 * indeks (status, next_retry_at) trzyma zapytanie rezerwujące selektywnym niezależnie od
 * liczby SENT-ów - tylko o to, że payload niesie SUROWY token weryfikacyjny albo resetu
 * hasła. pending_registrations trzyma wyłącznie SHA-256, żeby odczyt bazy nie dawał
 * użytecznego sekretu; bezterminowo trzymany wiersz outboxu znosił sens tego hashowania.
 *
 * Najważniejszy test to oldFailedMessage_shouldStay. Sprzątaczka kasująca "stare wiersze"
 * bez rozróżnienia statusu wyzerowałaby countFailed(), czyli jedyną odpowiedź na pytanie
 * "czy maile w ogóle wychodzą" - i awaria dostarczania wyglądałaby wtedy jak cisza.
 *
 * Wiersze ustawiamy przez JdbcTemplate, bo test musi kontrolować sent_at z dokładnością do
 * godzin w przeszłości; produkcyjne markSent() zawsze wpisuje moment faktycznej wysyłki.
 */
@SpringBootTest
@ActiveProfiles("test")
class OutboxRetentionTest {

    /** app.outbox.retention z konfiguracji bazowej; profil testowy tego nie nadpisuje. */
    private static final int RETENTION_HOURS = 24;

    @Autowired
    private OutboxMessageRepository repository;

    @Autowired
    private OutboxCleanupJob cleanupJob;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
    }

    private Long enqueued() {
        OutboxMessage saved = repository.save(new OutboxMessage(
                "retencja@example.com",
                MailTemplate.VERIFICATION,
                "{\"token\":\"5bd8d4cc-bce5-4d8f-bc9c-d6a65e6fb5d0\"}",
                LocalDateTime.now()));
        repository.flush();
        return saved.getId();
    }

    private Long sentHoursAgo(int hours) {
        Long id = enqueued();
        jdbcTemplate.update("update outbox_messages set status = 'SENT', sent_at = ? where id = ?",
                LocalDateTime.now().minusHours(hours), id);
        return id;
    }

    @Test
    @DisplayName("Wiadomość wysłana dawniej niż retencja jest usuwana")
    void sentBeforeRetention_shouldBeRemoved() {
        sentHoursAgo(RETENTION_HOURS + 1);

        assertThat(cleanupJob.removeSentOlderThanRetention()).isEqualTo(1);
        assertThat(repository.count()).isZero();
    }

    @Test
    @DisplayName("Wiadomość wysłana wewnątrz okna retencji zostaje")
    void sentWithinRetention_shouldStay() {
        sentHoursAgo(RETENTION_HOURS - 1);

        assertThat(cleanupJob.removeSentOlderThanRetention()).isZero();
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("Wiadomość FAILED zostaje niezależnie od wieku - na niej stoi countFailed()")
    void oldFailedMessage_shouldStay() {
        Long id = enqueued();
        jdbcTemplate.update(
                "update outbox_messages set status = 'FAILED', created_at = ? where id = ?",
                LocalDateTime.now().minusDays(30), id);

        assertThat(cleanupJob.removeSentOlderThanRetention()).isZero();
        assertThat(repository.count()).isEqualTo(1);
        // Sedno: sygnał monitoringu przeżywa sprzątanie. Gdyby nie przeżył, brak maili
        // byłby nie do odróżnienia od braku problemów.
        assertThat(repository.countFailed()).isEqualTo(1);
    }

    @Test
    @DisplayName("Wiadomość NEW zostaje niezależnie od wieku - jest jeszcze w obiegu")
    void oldNewMessage_shouldStay() {
        Long id = enqueued();
        jdbcTemplate.update(
                "update outbox_messages set created_at = ?, next_retry_at = ? where id = ?",
                LocalDateTime.now().minusDays(30), LocalDateTime.now().minusDays(30), id);

        assertThat(cleanupJob.removeSentOlderThanRetention()).isZero();
        assertThat(repository.count()).isEqualTo(1);
    }
}
