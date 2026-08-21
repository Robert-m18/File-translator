package com.example.filetranslator.common.web;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Zasada projektu mówi: nigdy nie logujemy adresów email ani innych danych osobowych.
 * Handler naruszeń więzów jest miejscem, w którym najłatwiej ją złamać niechcący, bo to
 * to baza, a nie kod aplikacji, wkłada wartość kolizji do komunikatu.
 *
 * Test jest jednostkowy i podpina własny appender zamiast wołać cały kontekst: sprawdzamy
 * treść LOGU, a nie odpowiedź HTTP, więc podniesienie Springa nic by tu nie wniosło.
 */
class GlobalExceptionHandlerTest {

    private static final String EMAIL = "ktos@example.com";

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        appender = new ListAppender<>();
        appender.start();
        logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
    }

    @Test
    @DisplayName("Naruszenie unikatu nie wpisuje adresu email do logu")
    void dataIntegrityViolation_shouldNotLogEmailAddress() {
        String mysqlMessage = "Duplicate entry '" + EMAIL + "' for key 'users.email'";

        // Taki dokładnie kształt ma prawdziwy wyjątek: SQLException sterownika w środku,
        // ConstraintViolationException Hibernate'a piętro wyżej, opakowanie Springa na
        // wierzchu. Zagnieżdżenie jest tu istotą testu - getMostSpecificCause() zszedłby
        // do najgłębszego, czyli do komunikatu z adresem.
        var hibernateCause = new ConstraintViolationException(
                mysqlMessage, new SQLException(mysqlMessage), "users.email");

        ProblemDetail problem = handler.handleDataIntegrity(
                new DataIntegrityViolationException("could not execute statement", hibernateCause));

        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.get(0);

        assertThat(event.getLevel()).isEqualTo(Level.WARN); // czyli przechodzi przez próg produkcyjny
        assertThat(event.getFormattedMessage())
                .doesNotContain(EMAIL)
                // Nazwa więzu mówi diagnostycznie tyle samo, a nie jest daną osobową
                .contains("users.email");

        // Odpowiedź bez zmian - nadal nie zdradza, że kolizja dotyczyła zajętego adresu
        assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
    }

    @Test
    @DisplayName("Nierozpoznane naruszenie loguje typ przyczyny, nie jej komunikat")
    void unknownViolation_shouldLogCauseTypeOnly() {
        String message = "cokolwiek, co sterownik zechce tam wsadzić: " + EMAIL;

        ProblemDetail problem = handler.handleDataIntegrity(
                new DataIntegrityViolationException(message, new SQLException(message)));

        assertThat(appender.list).hasSize(1);
        // Bez nazwy więzu nie wiadomo, co niesie komunikat - więc nie niesiemy go wcale
        assertThat(appender.list.get(0).getFormattedMessage())
                .doesNotContain(EMAIL)
                .contains("SQLException");

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
    }
}
