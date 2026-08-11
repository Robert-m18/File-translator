package com.example.robert.translation;

import com.example.robert.translation.repository.TranslationJobRepository;
import com.example.robert.user.UserRepository;
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
