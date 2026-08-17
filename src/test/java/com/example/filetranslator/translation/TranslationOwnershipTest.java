package com.example.filetranslator.translation;

import com.example.filetranslator.translation.repository.TranslationJobRepository;
import com.example.filetranslator.user.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Zlecenie należy do jednego użytkownika i nikt inny go nie widzi.
 *
 * SEDNO: cudze zlecenie zwraca 404, a NIE 403. To nie jest kosmetyka statusu. Odpowiedź 403
 * potwierdza, że zasób o tym identyfikatorze istnieje - a skoro identyfikatory są kolejnymi
 * liczbami, wystarczy przejechać pętlą po zakresie, żeby policzyć, ile zleceń przetworzył
 * system i kiedy przybywa ich najwięcej. 404 dla obu przypadków odbiera tę możliwość.
 *
 * Test pilnuje przy okazji tego, że rozróżnienia nie da się przypadkiem wprowadzić z powrotem:
 * zapytania biorą userId do WHERE, więc kod nie ma jak odróżnić "nie ma" od "nie twoje".
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TranslationOwnershipTest {

    private static final String OWNER_EMAIL = "wlasciciel@example.com";
    private static final String INTRUDER_EMAIL = "obcy@example.com";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TranslationJobRepository jobRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private Cookie ownerToken;
    private Cookie intruderToken;
    private Long jobId;

    @BeforeEach
    void setUp() throws Exception {
        jobRepository.deleteAll();

        TranslationTestSupport.createUser(userRepository, passwordEncoder, OWNER_EMAIL, "Właściciel");
        TranslationTestSupport.createUser(userRepository, passwordEncoder, INTRUDER_EMAIL, "Obcy");

        ownerToken = TranslationTestSupport.login(mockMvc, OWNER_EMAIL);
        intruderToken = TranslationTestSupport.login(mockMvc, INTRUDER_EMAIL);

        jobId = submitAs(ownerToken);
    }

    private Long submitAs(Cookie token) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "tajne.txt", "text/plain",
                "Poufna treść".getBytes(StandardCharsets.UTF_8));

        MvcResult result = mockMvc.perform(multipart("/translations")
                        .file(file)
                        .param("targetLang", "EN_GB")
                        .cookie(token)
                        .with(csrf()))
                .andExpect(status().isAccepted())
                .andReturn();

        return com.jayway.jsonpath.JsonPath.parse(result.getResponse().getContentAsString())
                .read("$.id", Integer.class).longValue();
    }

    @Test
    @DisplayName("Właściciel widzi swoje zlecenie")
    void owner_shouldSeeOwnJob() throws Exception {
        mockMvc.perform(get("/translations/{id}", jobId).cookie(ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(jobId))
                .andExpect(jsonPath("$.originalFilename").value("tajne.txt"));
    }

    @Test
    @DisplayName("Obcy dostaje 404 na cudzym zleceniu, a nie 403")
    void intruder_shouldGet404OnForeignJob() throws Exception {
        mockMvc.perform(get("/translations/{id}", jobId).cookie(intruderToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TRANSLATION_JOB_NOT_FOUND"));
    }

    /**
     * Nieistniejące zlecenie musi dawać ODPOWIEDŹ NIE DO ODRÓŻNIENIA od cudzego -
     * inaczej cała ostrożność z poprzedniego testu nic nie daje.
     */
    @Test
    @DisplayName("Nieistniejące i cudze zlecenie odpowiadają identycznie")
    void unknownAndForeignJob_shouldBeIndistinguishable() throws Exception {
        String foreign = mockMvc.perform(get("/translations/{id}", jobId).cookie(intruderToken))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();

        String unknown = mockMvc.perform(get("/translations/{id}", 999_999L).cookie(intruderToken))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();

        // timestamp i traceId różnią się z natury - porównujemy to, co niesie informację
        assertThat(codeOf(foreign)).isEqualTo(codeOf(unknown));
        assertThat(detailOf(foreign)).isEqualTo(detailOf(unknown));
    }

    @Test
    @DisplayName("Obcy nie pobierze treści cudzego zlecenia")
    void intruder_shouldNotDownloadForeignContent() throws Exception {
        mockMvc.perform(get("/translations/{id}/content", jobId).cookie(intruderToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TRANSLATION_JOB_NOT_FOUND"));
    }

    @Test
    @DisplayName("Obcy nie skasuje cudzego zlecenia")
    void intruder_shouldNotDeleteForeignJob() throws Exception {
        mockMvc.perform(delete("/translations/{id}", jobId)
                        .cookie(intruderToken)
                        .with(csrf()))
                .andExpect(status().isNotFound());

        assertThat(jobRepository.existsById(jobId))
                .as("zlecenie miało przetrwać próbę skasowania przez obcego")
                .isTrue();
    }

    @Test
    @DisplayName("Właściciel kasuje swoje zlecenie")
    void owner_shouldDeleteOwnJob() throws Exception {
        mockMvc.perform(delete("/translations/{id}", jobId)
                        .cookie(ownerToken)
                        .with(csrf()))
                .andExpect(status().isNoContent());

        assertThat(jobRepository.existsById(jobId)).isFalse();
    }

    /**
     * Lista jest listą WŁASNYCH zleceń - to jest ten sam warunek na user_id, tylko widoczny
     * z innej strony. Bez niego każdy widziałby cudze nazwy plików i tempo pracy.
     */
    @Test
    @DisplayName("Lista pokazuje wyłącznie własne zlecenia")
    void list_shouldContainOnlyOwnJobs() throws Exception {
        submitAs(intruderToken);

        mockMvc.perform(get("/translations").cookie(intruderToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(org.hamcrest.Matchers.not(jobId)));

        mockMvc.perform(get("/translations").cookie(ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(jobId));
    }

    /**
     * Zlecenie istnieje i należy do pytającego, ale nie ma jeszcze wyniku (worker jest
     * na testach wyłączony). To musi być 409 ze statusem, a nie 404 - front ma wiedzieć,
     * że warto odpytać ponownie.
     */
    @Test
    @DisplayName("Własne, jeszcze nieprzetworzone zlecenie daje 409 ze statusem")
    void owner_shouldGet409OnUnfinishedJob() throws Exception {
        mockMvc.perform(get("/translations/{id}/content", jobId).cookie(ownerToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TRANSLATION_NOT_READY"))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    @DisplayName("Niezalogowany dostaje 401, a nie 404")
    void anonymous_shouldGet401() throws Exception {
        mockMvc.perform(get("/translations/{id}", jobId))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/translations"))
                .andExpect(status().isUnauthorized());
    }

    private String codeOf(String body) {
        return com.jayway.jsonpath.JsonPath.parse(body).read("$.code");
    }

    private String detailOf(String body) {
        return com.jayway.jsonpath.JsonPath.parse(body).read("$.detail");
    }
}
