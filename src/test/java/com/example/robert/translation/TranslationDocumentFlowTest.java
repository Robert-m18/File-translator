package com.example.robert.translation;

import com.example.robert.translation.model.TranslationStatus;
import com.example.robert.translation.provider.DocumentHandle;
import com.example.robert.translation.provider.DocumentStatus;
import com.example.robert.translation.provider.DocumentUnavailableException;
import com.example.robert.translation.provider.TranslationProvider;
import com.example.robert.translation.repository.TranslationJobRepository;
import com.example.robert.translation.storage.ObjectStore;
import com.example.robert.user.UserRepository;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static com.example.robert.TestTime.sql;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Dokumenty (PDF) - ścieżka asynchroniczna: wgranie, odpytywanie, pobranie.
 *
 * MIARĄ JEST LICZBA WGRAŃ DOKUMENTU, a nie status zlecenia. Status wychodzi DONE niezależnie
 * od tego, czy uchwyt został zapisany, czy dokument wgrano po raz drugi - a to właśnie ta
 * różnica jest tu jedyną rzeczą wartą pilnowania, bo u dostawcy płaci się za każde wgranie.
 * Ta sama zasada co w TranslationDedupTest, gdzie miarą jest liczba wywołań dostawcy.
 *
 * Adnotacje kontekstu są IDENTYCZNE jak w TranslationDedupTest (ten sam @TestPropertySource,
 * ten sam jeden @MockitoBean) i to jest celowe: dzięki temu obie klasy dzielą jeden kontekst
 * Springa zamiast zakładać drugi. Każdy dodatkowy kontekst to kolejna pula połączeń trzymana
 * do końca JVM-a, a suite wywróciła się już raz na wyczerpanym max_connections.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "app.translation.daily-char-limit=100")
class TranslationDocumentFlowTest {

    private static final String EMAIL = "dokumenty@example.com";
    private static final DocumentHandle HANDLE = new DocumentHandle("doc-1", "key-1");

    @MockitoBean
    private TranslationProvider provider;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TranslationJobWorker worker;

    @Autowired
    private TranslationJobRepository jobRepository;

    @Autowired
    private ObjectStore objectStore;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Cookie accessToken;

    @BeforeEach
    void setUp() throws Exception {
        jobRepository.deleteAll();
        TranslationTestSupport.createUser(userRepository, passwordEncoder, EMAIL, "Dokumenty");
        accessToken = TranslationTestSupport.login(mockMvc, EMAIL);

        when(provider.uploadDocument(any(), anyString(), any())).thenReturn(HANDLE);
    }

    /** Minimalne bajty wyglądające jak PDF - liczy się sygnatura, bo jej szuka FileType. */
    private static byte[] pdf(String payload) {
        return ("%PDF-" + payload).getBytes(StandardCharsets.UTF_8);
    }

    private long submitPdf() throws Exception {
        MvcResult result = mockMvc.perform(multipart("/translations")
                        .file(new MockMultipartFile("file", "umowa.pdf", "application/pdf", pdf("tresc")))
                        .param("targetLang", "EN_GB")
                        .cookie(accessToken)
                        .with(csrf()))
                .andExpect(status().isAccepted())
                .andReturn();

        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
    }

    /**
     * Cofa next_attempt_at, żeby zlecenie było znów gotowe do wzięcia bez czekania na odstęp
     * między odpytaniami. Instant przez TestTime.sql - sterownik PostgreSQL-a nie zbinduje
     * go inaczej, a H2 (profil -Ph2) przyjąłby go bez mrugnięcia i błąd ukrywałby się tam.
     */
    private void makeReady() {
        jdbcTemplate.update("update translation_jobs set next_attempt_at = ? where status <> 'DONE'",
                sql(Instant.now().minusSeconds(1)));
    }

    private DocumentStatus done(int billedCharacters) {
        return new DocumentStatus(DocumentStatus.State.DONE, billedCharacters, null);
    }

    private DocumentStatus translating() {
        return new DocumentStatus(DocumentStatus.State.TRANSLATING, null, null);
    }

    /**
     * Ścieżka podstawowa. Sprawdza przy okazji rzecz, której nie widać po statusie: dokument
     * NIE idzie tekstowym API - gdyby szedł, PDF pojechałby do dostawcy jako łańcuch znaków
     * i wrócił uszkodzony.
     */
    @Test
    @DisplayName("Dokument przechodzi przez wgranie, sprawdzenie i pobranie")
    void document_shouldGoThroughUploadCheckDownload() throws Exception {
        when(provider.checkDocument(HANDLE)).thenReturn(done(1234));
        when(provider.downloadDocument(HANDLE)).thenReturn(pdf("przetlumaczone"));

        long id = submitPdf();
        assertThat(worker.processBatch()).isEqualTo(1);

        var job = jobRepository.findById(id).orElseThrow();
        assertThat(job.getStatus()).isEqualTo(TranslationStatus.DONE);
        assertThat(job.getResultObjectKey()).endsWith("/result.pdf");
        assertThat(objectStore.read(job.getResultObjectKey())).isEqualTo(pdf("przetlumaczone"));

        // Liczba znaków dokumentu jest znana DOPIERO od dostawcy - u nas nie ma jej skąd wziąć.
        assertThat(job.getBilledChars()).isEqualTo(1234);
        assertThat(job.getCharCount()).isEqualTo(1234);

        verify(provider, never()).translate(anyString(), any());
        // Uchwyt sprzątnięty po zakończeniu - nie ma już czego wznawiać.
        assertThat(job.getProviderDocumentId()).isNull();
    }

    /**
     * SEDNO CAŁEJ ŚCIEŻKI DOKUMENTOWEJ. Dostawca tłumaczy dłużej niż jedno podejście, więc
     * zlecenie wraca do kolejki - i przy kolejnym wzięciu ma WRÓCIĆ PO WYNIK, a nie wgrać
     * dokument od nowa. Bez uchwytu zapisanego w wierszu drugie wzięcie zapłaciłoby drugi raz.
     *
     * Dyskryminuje: usunięcie kolumn provider_document_id/key, pominięcie saveDocumentHandle
     * albo czytanie uchwytu z encji zamiast z bazy.
     */
    @Test
    @DisplayName("Dokument tłumaczony dłużej niż jedno podejście NIE jest wgrywany drugi raz")
    void slowDocument_shouldResumeInsteadOfUploadingAgain() throws Exception {
        when(provider.checkDocument(HANDLE)).thenReturn(translating(), done(50));
        when(provider.downloadDocument(HANDLE)).thenReturn(pdf("gotowe"));

        long id = submitPdf();

        // Pierwsze podejście: dostawca jeszcze tłumaczy, zlecenie wraca do kolejki
        assertThat(worker.processBatch()).isZero();
        var afterFirst = jobRepository.findById(id).orElseThrow();
        assertThat(afterFirst.getStatus()).isNotEqualTo(TranslationStatus.DONE);
        assertThat(afterFirst.getProviderDocumentId())
                .as("uchwyt musi przeżyć podejście, inaczej nie ma po czym wrócić")
                .isEqualTo(HANDLE.documentId());

        // Drugie podejście: gotowe
        makeReady();
        assertThat(worker.processBatch()).isEqualTo(1);
        assertThat(jobRepository.findById(id).orElseThrow().getStatus()).isEqualTo(TranslationStatus.DONE);

        verify(provider, times(1)).uploadDocument(any(), anyString(), any());
    }

    /**
     * Odpytywanie NIE JEST podejściem i nie może wyczerpać limitu prób.
     *
     * Profil testowy ma max-attempts = 2, więc bez odjęcia licznika w markPolling dokument
     * tłumaczony dłużej niż dwa sprawdzenia zostałby PORZUCONY jako nieudany - mimo że po
     * stronie dostawcy wszystko idzie dobrze. Tu sprawdzamy trzy sprawdzenia z rzędu.
     */
    @Test
    @DisplayName("Kolejne odpytania nie zużywają limitu prób")
    void polling_shouldNotConsumeAttempts() throws Exception {
        when(provider.checkDocument(HANDLE)).thenReturn(translating());

        long id = submitPdf();
        for (int i = 0; i < 3; i++) {
            makeReady();
            assertThat(worker.processBatch()).isZero();
        }

        var job = jobRepository.findById(id).orElseThrow();
        assertThat(job.getStatus())
                .as("dokument wciąż tłumaczony nie może skończyć jako FAILED")
                .isNotEqualTo(TranslationStatus.FAILED);
        assertThat(job.getAttempts())
                .as("licznik podejść ma znaczyć nieudane próby, a nie liczbę zajrzeń")
                .isLessThanOrEqualTo(1);
    }

    /**
     * Dokument zniknął u dostawcy (pobrać można go tylko RAZ). Uchwyt trzeba wtedy wyczyścić,
     * żeby kolejne podejście zaczęło od nowa - bez tego zlecenie pytałoby o nieistniejący
     * dokument aż do wyczerpania prób i umarło, choć wystarczy wgrać plik jeszcze raz.
     */
    @Test
    @DisplayName("Zniknięcie dokumentu u dostawcy czyści uchwyt i pozwala zacząć od nowa")
    void documentGone_shouldClearHandleAndStartOver() throws Exception {
        when(provider.checkDocument(HANDLE)).thenReturn(done(10));
        when(provider.downloadDocument(HANDLE))
                .thenThrow(new DocumentUnavailableException("już pobrany"))
                .thenReturn(pdf("za drugim razem"));

        long id = submitPdf();
        assertThat(worker.processBatch()).isZero();

        var afterLoss = jobRepository.findById(id).orElseThrow();
        assertThat(afterLoss.getProviderDocumentId())
                .as("uchwyt do nieistniejącego dokumentu musi zniknąć")
                .isNull();

        makeReady();
        assertThat(worker.processBatch()).isEqualTo(1);

        // Drugie wgranie jest tu KOSZTEM, nie błędem - dostawca skasował dokument po pobraniu
        verify(provider, times(2)).uploadDocument(any(), anyString(), any());
        assertThat(jobRepository.findById(id).orElseThrow().getStatus()).isEqualTo(TranslationStatus.DONE);
    }
}
