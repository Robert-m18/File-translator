package com.example.robert.translation;

import com.example.robert.common.security.TokenHasher;
import com.example.robert.common.time.DbClock;
import com.example.robert.notification.MailOutbox;
import com.example.robert.notification.OutboxMessageRepository;
import com.example.robert.notification.model.MailTemplate;
import com.example.robert.notification.model.OutboxMessage;
import com.example.robert.translation.model.TargetLanguage;
import com.example.robert.translation.model.TranslationJob;
import com.example.robert.translation.model.TranslationStatus;
import com.example.robert.translation.provider.TranslationProvider;
import com.example.robert.translation.provider.TranslationProviderException;
import com.example.robert.translation.provider.TranslationResult;
import com.example.robert.translation.repository.TranslationJobRepository;
import com.example.robert.user.UserRepository;
import com.example.robert.user.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * Powiadomienie o gotowym tłumaczeniu jedzie przez skrzynkę nadawczą, w transakcji zapisu
 * wyniku.
 *
 * Trzy pytania, na które ta klasa odpowiada, i wszystkie trzy są o TRWAŁOŚĆ, nie o treść:
 *  - czy udane tłumaczenie w ogóle zamawia mail,
 *  - czy nieudane go NIE zamawia (mail "gotowe" do zlecenia, które padło, byłby gorszy
 *    niż brak maila),
 *  - czy zamówienie i wynik są NIEROZŁĄCZNE - jedno bez drugiego nie może się zapisać.
 */
@SpringBootTest
@ActiveProfiles("test")
class TranslationNotificationTest {

    private static final String EMAIL = "powiadomienia@example.com";

    @MockitoBean
    private TranslationProvider provider;

    /**
     * Szpieg, a nie atrapa: dwa pierwsze testy mają przejść przez PRAWDZIWY zapis do skrzynki
     * nadawczej i sprawdzić wiersz w bazie. Wyjątek podstawiamy tylko w trzecim.
     */
    @MockitoSpyBean
    private MailOutbox mailOutbox;

    @Autowired
    private TranslationJobWorker worker;

    @Autowired
    private TranslationJobRepository jobRepository;

    @Autowired
    private OutboxMessageRepository outboxRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private User owner;

    @BeforeEach
    void setUp() {
        jobRepository.deleteAll();
        outboxRepository.deleteAll();
        owner = TranslationTestSupport.createUser(userRepository, passwordEncoder, EMAIL, "Adresat");
    }

    private TranslationJob newJob() {
        return jobRepository.save(new TranslationJob(
                owner, "raport.txt", TargetLanguage.DE, "Ala ma kota",
                TokenHasher.sha256Hex("Ala ma kota"), DbClock.now()));
    }

    @Test
    @DisplayName("Udane tłumaczenie zamawia mail z nazwą pliku, bez treści tłumaczenia")
    void success_shouldEnqueueMail() {
        when(provider.translate(anyString(), any()))
                .thenReturn(new TranslationResult("Anna hat eine Katze", "PL"));

        newJob();
        assertThat(worker.processBatch()).isEqualTo(1);

        assertThat(outboxRepository.count()).isEqualTo(1);
        OutboxMessage message = outboxRepository.findAll().get(0);

        assertThat(message.getTemplate()).isEqualTo(MailTemplate.TRANSLATION_DONE);
        assertThat(message.getRecipient()).isEqualTo(EMAIL);
        assertThat(message.getStatus()).isEqualTo(OutboxMessage.Status.NEW);
        assertThat(message.getPayload()).contains("raport.txt");

        // Payload leży w bazie plaintekstem i przeżywa retencję zlecenia - treść tłumaczenia
        // nie ma prawa się w nim znaleźć, bo byłaby drugą, nieobjętą niczym kopią danych.
        assertThat(message.getPayload())
                .as("payload maila nie może nieść treści tłumaczenia")
                .doesNotContain("Anna hat eine Katze");
    }

    @Test
    @DisplayName("Nieudane tłumaczenie nie zamawia żadnego maila")
    void failure_shouldNotEnqueueMail() {
        when(provider.translate(anyString(), any()))
                .thenThrow(new TranslationProviderException("PROVIDER_REJECTED", false, "400"));

        TranslationJob job = newJob();
        worker.processBatch();

        assertThat(jobRepository.findById(job.getId()).orElseThrow().getStatus())
                .isEqualTo(TranslationStatus.FAILED);
        assertThat(outboxRepository.count())
                .as("mail 'tłumaczenie gotowe' do zlecenia, które padło, byłby gorszy niż brak maila")
                .isZero();
    }

    /**
     * SEDNO wzorca: zamówienie maila i zapis wyniku są w jednej transakcji, więc nie da się
     * zapisać jednego bez drugiego. Sprawdzane od strony, którą da się wymusić - gdy zapis
     * zamówienia pęknie, wynik tłumaczenia NIE MOŻE zostać zatwierdzony.
     *
     * Gdyby te dwie rzeczy były w osobnych transakcjach, zlecenie byłoby DONE, a użytkownik
     * nie dostałby powiadomienia i nikt by się o tym nie dowiedział - klasyczny dual write.
     * Zlecenie zostaje wtedy w kolejce i wróci po upływie rezerwacji.
     */
    @Test
    @DisplayName("Porażka zapisu zamówienia wycofuje także zapis wyniku")
    void failedMailOrder_shouldRollBackResult() {
        when(provider.translate(anyString(), any()))
                .thenReturn(new TranslationResult("Anna hat eine Katze", "PL"));
        doThrow(new IllegalStateException("baza padła przy zapisie zamówienia"))
                .when(mailOutbox).enqueueTranslationDone(anyString(), anyString(), anyString());

        TranslationJob job = newJob();
        worker.processBatch();

        assertThat(jobRepository.findById(job.getId()).orElseThrow().getStatus())
                .as("wynik nie mógł zostać zatwierdzony bez zamówienia powiadomienia")
                .isNotEqualTo(TranslationStatus.DONE);
        assertThat(outboxRepository.count()).isZero();
    }
}
