package com.example.robert.translation;

import com.example.robert.common.security.TokenHasher;
import com.example.robert.common.time.DbClock;
import com.example.robert.translation.model.TargetLanguage;
import com.example.robert.translation.model.TranslationJob;
import com.example.robert.translation.model.TranslationStatus;
import com.example.robert.translation.repository.TranslationJobRepository;
import com.example.robert.user.UserRepository;
import com.example.robert.user.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.Instant;

import static com.example.robert.TestTime.sql;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Retencja zleceń tłumaczenia.
 *
 * Powodem retencji jest TREŚĆ, nie rozmiar tabeli: w kolumnach leżą pliki użytkowników.
 * Bez niej wyciek bazy oddaje wszystko, co ktokolwiek kiedykolwiek przetłumaczył.
 */
@SpringBootTest
@ActiveProfiles("test")
class TranslationRetentionTest {

    @Autowired
    private TranslationCleanupJob cleanupJob;

    @Autowired
    private TranslationJobRepository jobRepository;

    @Autowired
    private TranslationProperties properties;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private User owner;

    @BeforeEach
    void setUp() {
        jobRepository.deleteAll();
        owner = TranslationTestSupport.createUser(userRepository, passwordEncoder,
                "retencja@example.com", "Retencja");
    }

    /**
     * created_at ustawiamy przez SQL, bo encja nadaje je w konstruktorze. Instant przez
     * TestTime.sql - sterownik PostgreSQL-a nie zbinduje go inaczej, a H2 przyjąłby go
     * bez mrugnięcia i błąd wyszedłby dopiero w jobie "integration".
     */
    private Long jobCreatedAgo(Duration age, TranslationStatus status) {
        TranslationJob job = jobRepository.save(new TranslationJob(
                owner, "plik.txt", TargetLanguage.EN_GB, "Ala ma kota",
                TokenHasher.sha256Hex("Ala ma kota"), DbClock.now()));

        jdbcTemplate.update("update translation_jobs set created_at = ?, status = ? where id = ?",
                sql(Instant.now().minus(age)), status.name(), job.getId());

        return job.getId();
    }

    @Test
    @DisplayName("Zlecenie starsze niż retencja jest usuwane")
    void oldJob_shouldBeRemoved() {
        Long old = jobCreatedAgo(properties.retention().plusDays(1), TranslationStatus.DONE);

        assertThat(cleanupJob.removeOlderThanRetention()).isEqualTo(1);
        assertThat(jobRepository.existsById(old)).isFalse();
    }

    @Test
    @DisplayName("Zlecenie mieszczące się w retencji zostaje")
    void recentJob_shouldSurvive() {
        Long recent = jobCreatedAgo(properties.retention().minusDays(1), TranslationStatus.DONE);

        assertThat(cleanupJob.removeOlderThanRetention()).isZero();
        assertThat(jobRepository.existsById(recent)).isTrue();
    }

    /**
     * W odróżnieniu od skrzynki nadawczej kasujemy WSZYSTKIE statusy. Tam FAILED musi
     * przetrwać, bo countFailed() jest jedynym sygnałem "czy maile wychodzą". Tutaj nieudane
     * zlecenie sprzed miesiąca nie odpowiada na żadne pytanie, a niesie dokładnie tę samą
     * treść pliku co udane - czyli dokładnie to, przed czym retencja ma chronić.
     */
    @Test
    @DisplayName("Retencja obejmuje także zlecenia nieudane i niedokończone")
    void allStatuses_shouldBeRemoved() {
        Duration old = properties.retention().plusDays(1);
        jobCreatedAgo(old, TranslationStatus.DONE);
        jobCreatedAgo(old, TranslationStatus.FAILED);
        jobCreatedAgo(old, TranslationStatus.PENDING);
        jobCreatedAgo(old, TranslationStatus.PROCESSING);

        assertThat(cleanupJob.removeOlderThanRetention()).isEqualTo(4);
        assertThat(jobRepository.count()).isZero();
    }

    /**
     * Odliczanie od created_at, a NIE od completed_at. Zlecenie, które nigdy się nie
     * zakończyło, ma completed_at puste - przy odliczaniu od niego zostawałoby na zawsze,
     * czyli akurat najbardziej zapomniane wiersze byłyby jedynymi nieusuwalnymi.
     */
    @Test
    @DisplayName("Stare zlecenie bez daty zakończenia też jest usuwane")
    void oldUnfinishedJob_shouldBeRemoved() {
        Long stuck = jobCreatedAgo(properties.retention().plusDays(5), TranslationStatus.PENDING);

        assertThat(jobRepository.findById(stuck).orElseThrow().getCompletedAt()).isNull();
        assertThat(cleanupJob.removeOlderThanRetention()).isEqualTo(1);
        assertThat(jobRepository.existsById(stuck)).isFalse();
    }
}
