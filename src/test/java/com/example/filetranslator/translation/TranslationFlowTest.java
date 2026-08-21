package com.example.filetranslator.translation;

import com.example.filetranslator.translation.model.TranslationJob;
import com.example.filetranslator.translation.repository.TranslationJobRepository;
import com.example.filetranslator.translation.storage.ObjectStore;
import com.example.filetranslator.user.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Przekrój od końca do końca: plik wchodzi, zlecenie czeka, worker je bierze, plik wychodzi.
 *
 * Worker wołany jest WPROST (processBatch), a nie przez harmonogram - inaczej wynik testu
 * zależałby od tego, co zdąży się wykonać w tle, i klasa byłaby niestabilna z definicji.
 * Na profilu testowym harmonogram jest zresztą wyłączony (app.translation.enabled=false).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TranslationFlowTest {

    private static final String EMAIL = "przeplyw@example.com";
    private static final String CONTENT = "Ala ma kota\nKot ma Alę";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TranslationJobRepository jobRepository;

    @Autowired
    private TranslationJobWorker worker;

    @Autowired
    private ObjectStore objectStore;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private Cookie accessToken;

    @BeforeEach
    void setUp() throws Exception {
        jobRepository.deleteAll();
        TranslationTestSupport.createUser(userRepository, passwordEncoder, EMAIL, "Przepływ");
        accessToken = TranslationTestSupport.login(mockMvc, EMAIL);
    }

    private Long submit() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "lista.txt", "text/plain",
                CONTENT.getBytes(StandardCharsets.UTF_8));

        MvcResult result = mockMvc.perform(multipart("/translations")
                        .file(file)
                        .param("targetLang", "EN_GB")
                        .cookie(accessToken)
                        .with(csrf()))
                .andExpect(status().isAccepted())
                .andReturn();

        return com.jayway.jsonpath.JsonPath.parse(result.getResponse().getContentAsString())
                .read("$.id", Integer.class).longValue();
    }

    @Test
    @DisplayName("Zlecenie przechodzi przez kolejkę i wraca jako plik do pobrania")
    void job_shouldTravelFromUploadToDownload() throws Exception {
        Long id = submit();

        mockMvc.perform(get("/translations/{id}", id).cookie(accessToken))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.completedAt").doesNotExist());

        assertThat(worker.processBatch())
                .as("worker miał wziąć dokładnie jedno zlecenie")
                .isEqualTo(1);

        mockMvc.perform(get("/translations/{id}", id).cookie(accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.completedAt").exists())
                // Wykryty język źródła uzupełnia dostawca - atrapa mówi wprost, że nie wie
                .andExpect(jsonPath("$.sourceLang").value("AUTO"));

        MvcResult download = mockMvc.perform(get("/translations/{id}/content", id).cookie(accessToken))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "text/plain;charset=UTF-8"))
                .andReturn();

        String body = download.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).isEqualTo("[[EN-GB]] Ala ma kota\n[[EN-GB]] Kot ma Alę");
    }

    /**
     * Nagłówek pobierania: attachment (przeglądarka zapisuje plik, nie wyświetla go)
     * i nazwa z dopiskiem języka, żeby oryginał i tłumaczenie nie nadpisywały się w folderze
     * pobranych.
     */
    @Test
    @DisplayName("Wynik wraca jako załącznik z nazwą zawierającą język docelowy")
    void download_shouldCarryAttachmentFilename() throws Exception {
        Long id = submit();
        worker.processBatch();

        mockMvc.perform(get("/translations/{id}/content", id).cookie(accessToken))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        org.hamcrest.Matchers.containsString("attachment")))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        org.hamcrest.Matchers.containsString("lista-EN-GB.txt")));
    }

    @Test
    @DisplayName("Pusty cykl workera nie robi nic")
    void emptyQueue_shouldDoNothing() {
        assertThat(worker.processBatch()).isZero();
    }

    /**
     * Plik trafia do MAGAZYNU OBIEKTOWEGO, a w wierszu zostaje sam klucz.
     *
     * Asercja na treści w magazynie, a nie na tym, że kolumna czegoś nie zawiera: gdyby ktoś
     * przywrócił trzymanie treści w bazie, ten test przeszedłby na samym istnieniu klucza.
     * Test sprawdza więc, że pod kluczem z wiersza faktycznie leży wgrany plik - to jedyna
     * asercja, której nie da się spełnić bez działającego zapisu do magazynu.
     */
    @Test
    @DisplayName("Źródło i wynik leżą w magazynie pod kluczami z wiersza zlecenia")
    void job_shouldStoreFilesUnderKeysFromRow() throws Exception {
        Long id = submit();

        TranslationJob afterSubmit = jobRepository.findById(id).orElseThrow();
        assertThat(afterSubmit.getSourceObjectKey())
                .as("klucz źródła ma nieść identyfikator właściciela i UUID zlecenia")
                .matches("users/\\d+/jobs/[0-9a-f-]{36}/source\\.txt");
        assertThat(new String(objectStore.read(afterSubmit.getSourceObjectKey()), StandardCharsets.UTF_8))
                .isEqualTo(CONTENT);
        assertThat(afterSubmit.getResultObjectKey())
                .as("wynik nie istnieje, dopóki worker nie przetłumaczy")
                .isNull();

        worker.processBatch();

        TranslationJob afterWorker = jobRepository.findById(id).orElseThrow();
        // Wynik leży pod TYM SAMYM prefiksem co źródło - to jest właśnie wyłączność
        // zlecenia na swój prefiks, dzięki której kasowanie jest jednym wywołaniem.
        assertThat(afterWorker.getResultObjectKey())
                .isEqualTo(afterSubmit.getSourceObjectKey().replace("source.txt", "result.txt"));
        assertThat(new String(objectStore.read(afterWorker.getResultObjectKey()), StandardCharsets.UTF_8))
                .isEqualTo("[[EN-GB]] Ala ma kota\n[[EN-GB]] Kot ma Alę");
    }

    /**
     * Skasowanie zlecenia ma zabrać PLIKI, a nie tylko wiersz.
     *
     * Bez tego kroku "usuń moje tłumaczenie" zostawiałoby treść pliku w magazynie na kolejne
     * dni - a to jest jedyny sposób, w jaki użytkownik może usunąć swój plik z serwera przed
     * upływem retencji. Reguła wygasania na kubełku by go w końcu sprzątnęła, ale "w końcu"
     * nie jest odpowiedzią na żądanie usunięcia danych.
     */
    @Test
    @DisplayName("Skasowanie zlecenia usuwa jego pliki z magazynu")
    void delete_shouldRemoveFilesFromStorage() throws Exception {
        Long id = submit();
        worker.processBatch();

        TranslationJob job = jobRepository.findById(id).orElseThrow();
        String sourceKey = job.getSourceObjectKey();
        String resultKey = job.getResultObjectKey();
        assertThat(objectStore.exists(sourceKey)).isTrue();
        assertThat(objectStore.exists(resultKey)).isTrue();

        mockMvc.perform(delete("/translations/{id}", id).cookie(accessToken).with(csrf()))
                .andExpect(status().isNoContent());

        assertThat(jobRepository.findById(id)).isEmpty();
        // OBA pliki, nie tylko wynik: źródło to też treść wgrana przez użytkownika.
        // Asercje idą przez interfejs, bez rzutowania na implementację w pamięci - w jobie
        // "integration" ten sam test chodzi na prawdziwym MinIO.
        assertThat(objectStore.exists(sourceKey))
                .as("źródło ma zniknąć razem ze zleceniem")
                .isFalse();
        assertThat(objectStore.exists(resultKey))
                .as("wynik ma zniknąć razem ze zleceniem")
                .isFalse();
    }

    /**
     * Zlecenie raz przetłumaczone nie może wrócić do kolejki - inaczej każdy cykl workera
     * tłumaczyłby je od nowa, płacąc za to znakami u dostawcy.
     */
    @Test
    @DisplayName("Zakończone zlecenie nie jest brane po raz drugi")
    void finishedJob_shouldNotBeClaimedAgain() throws Exception {
        submit();

        assertThat(worker.processBatch()).isEqualTo(1);
        assertThat(worker.processBatch()).isZero();
    }

    /**
     * Cała paczka w jednym cyklu - inaczej przy kilku zleceniach naraz użytkownik czekałby
     * tyle cykli, ile zleceń, choć rozmiar paczki na to pozwala.
     */
    @Test
    @DisplayName("Jeden cykl bierze całą paczkę")
    void batch_shouldBeProcessedInSingleCycle() throws Exception {
        submit();
        submit();
        submit();

        assertThat(worker.processBatch()).isEqualTo(3);
        assertThat(jobRepository.count()).isEqualTo(3);
    }
}
