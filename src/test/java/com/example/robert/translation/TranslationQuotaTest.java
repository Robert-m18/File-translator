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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Dobowy limit znaków na użytkownika.
 *
 * PO CO ON JEST, skoro istnieje limiter żądań: limiter liczy ŻĄDANIA, a u dostawcy płaci się
 * za ZNAKI. Trzydzieści żądań na godzinę mieści się w progu limitera i jednocześnie potrafi
 * wyczerpać cały miesięczny limit darmowego konta - a ten liczy się dla konta, nie dla
 * użytkownika, więc jedna osoba psuje wtedy usługę wszystkim pozostałym.
 *
 * Limit ustawiony niżej niż na profilu testowym przez @TestPropertySource, żeby nie budować
 * pliku o 50 tys. znaków. Osobny kontekst jest tu ceną, którą płacimy świadomie.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "app.translation.daily-char-limit=100")
class TranslationQuotaTest {

    private static final String EMAIL = "limit@example.com";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TranslationJobRepository jobRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private Cookie accessToken;

    @BeforeEach
    void setUp() throws Exception {
        jobRepository.deleteAll();
        TranslationTestSupport.createUser(userRepository, passwordEncoder, EMAIL, "Limit");
        accessToken = TranslationTestSupport.login(mockMvc, EMAIL);
    }

    private ResultActions submit(int chars) throws Exception {
        String content = "a".repeat(chars);
        MockMultipartFile file = new MockMultipartFile("file", "plik.txt", "text/plain",
                content.getBytes(StandardCharsets.UTF_8));

        return mockMvc.perform(multipart("/translations")
                .file(file)
                .param("targetLang", "EN_GB")
                .cookie(accessToken)
                .with(csrf()));
    }

    @Test
    @DisplayName("Zlecenie mieszczące się w limicie przechodzi")
    void withinLimit_shouldBeAccepted() throws Exception {
        submit(100).andExpect(status().isAccepted());
    }

    @Test
    @DisplayName("Zlecenie przekraczające limit wraca 429 i NIE powstaje")
    void overLimit_shouldBeRejected() throws Exception {
        submit(101)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("TRANSLATION_QUOTA_EXCEEDED"));

        assertThat(jobRepository.count())
                .as("odrzucone zlecenie nie może zostawić wiersza w kolejce")
                .isZero();
    }

    /**
     * Limit sumuje się przez dobę, a nie dotyczy pojedynczego pliku. Inaczej wystarczyłoby
     * podzielić tekst na kilka plików, żeby go obejść - czyli nie byłoby go wcale.
     */
    @Test
    @DisplayName("Limit sumuje wcześniejsze zlecenia z ostatniej doby")
    void limit_shouldAccumulateAcrossJobs() throws Exception {
        submit(60).andExpect(status().isAccepted());
        submit(30).andExpect(status().isAccepted());

        // Zostało 10 znaków
        submit(11).andExpect(status().isTooManyRequests());
        submit(10).andExpect(status().isAccepted());

        assertThat(jobRepository.count()).isEqualTo(3);
    }

    /** Odpowiedź mówi, ile jeszcze wolno - bez tego użytkownik widzi samą odmowę. */
    @Test
    @DisplayName("Odpowiedź niesie liczbę pozostałych znaków")
    void rejection_shouldReportRemainingChars() throws Exception {
        submit(90).andExpect(status().isAccepted());

        submit(50)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.remainingChars").value(10));
    }

    /**
     * Limit jest NA UŻYTKOWNIKA. Gdyby liczył się globalnie, jedna osoba odcinałaby
     * pozostałych - czyli zamieniałby ochronę budżetu w gotowe narzędzie odmowy usługi.
     */
    @Test
    @DisplayName("Limit jednego użytkownika nie blokuje drugiego")
    void limit_shouldBePerUser() throws Exception {
        submit(100).andExpect(status().isAccepted());
        submit(1).andExpect(status().isTooManyRequests());

        TranslationTestSupport.createUser(userRepository, passwordEncoder, "drugi@example.com", "Drugi");
        Cookie other = TranslationTestSupport.login(mockMvc, "drugi@example.com");

        mockMvc.perform(multipart("/translations")
                        .file(new MockMultipartFile("file", "plik.txt", "text/plain",
                                "a".repeat(100).getBytes(StandardCharsets.UTF_8)))
                        .param("targetLang", "EN_GB")
                        .cookie(other)
                        .with(csrf()))
                .andExpect(status().isAccepted());
    }
}
