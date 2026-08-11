package com.example.robert.translation;

import com.example.robert.translation.model.TargetLanguage;
import com.example.robert.translation.model.TranslationJob;
import com.example.robert.translation.model.TranslationStatus;
import com.example.robert.translation.provider.TranslationProvider;
import com.example.robert.translation.provider.TranslationProviderException;
import com.example.robert.translation.provider.TranslationResult;
import com.example.robert.translation.repository.TranslationJobRepository;
import com.example.robert.common.time.DbClock;
import com.example.robert.user.UserRepository;
import com.example.robert.user.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Limit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

import static com.example.robert.TestTime.sql;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Zachowanie workera przy porażkach dostawcy i przy paczce.
 *
 * Dostawca jest tu podmieniony na atrapę, bo o to właśnie chodzi: prawdziwy nie odmówi
 * na żądanie, a to odmowy są tu badane.
 */
@SpringBootTest
@ActiveProfiles("test")
class TranslationWorkerTest {

    @MockitoBean
    private TranslationProvider provider;

    @Autowired
    private TranslationJobWorker worker;

    @Autowired
    private TranslationJobRepository jobRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private TranslationProperties properties;

    private User owner;

    @BeforeEach
    void setUp() {
        jobRepository.deleteAll();
        owner = TranslationTestSupport.createUser(userRepository, passwordEncoder,
                "worker@example.com", "Worker");
    }

    private TranslationJob newJob(String content) {
        return jobRepository.save(new TranslationJob(
                owner, "plik.txt", TargetLanguage.EN_GB, content, DbClock.now()));
    }

    /**
     * Cofa next_attempt_at, żeby zlecenie było znów gotowe do wzięcia bez czekania na backoff.
     * Instant przez TestTime.sql - sterownik PostgreSQL-a nie zbinduje go inaczej, a H2
     * przyjąłby go bez mrugnięcia i błąd wyszedłby dopiero w jobie "integration".
     */
    private void makeReady() {
        jdbcTemplate.update("update translation_jobs set next_attempt_at = ? where status = 'PENDING'",
                sql(Instant.now().minusSeconds(1)));
    }

    private TranslationJob reload(Long id) {
        return jobRepository.findById(id).orElseThrow();
    }

    /**
     * Porażka PRZEJŚCIOWA: zlecenie wraca do kolejki z podbitym licznikiem i odsuniętym
     * terminem. Odsunięcie jest tu tak samo istotne jak status - bez niego worker brałby
     * to samo zlecenie w każdym cyklu, czyli dobijałby chwilowo niedostępnego dostawcę
     * co dwie sekundy.
     */
    @Test
    @DisplayName("Błąd przejściowy odkłada zlecenie na później, nie kończy go")
    void retryableFailure_shouldReturnJobToQueue() {
        when(provider.translate(anyString(), any()))
                .thenThrow(new TranslationProviderException("PROVIDER_UNAVAILABLE", true, "503"));

        TranslationJob job = newJob("Ala ma kota");
        Instant beforeRun = Instant.now();

        assertThat(worker.processBatch()).isZero();

        TranslationJob after = reload(job.getId());
        assertThat(after.getStatus()).isEqualTo(TranslationStatus.PENDING);
        assertThat(after.getAttempts()).isEqualTo(1);
        assertThat(after.getNextAttemptAt())
                .as("ponowienie ma być odsunięte o backoff")
                .isAfter(beforeRun);
        assertThat(after.getLastError()).contains("PROVIDER_UNAVAILABLE");
    }

    /**
     * SEDNO rozróżnienia retryable: błąd TRWAŁY kończy zlecenie od razu, nie czekając na
     * wyczerpanie prób. Nieprawidłowy klucz API czy nieobsługiwany język wrócą identycznie
     * za każdym razem, więc pełny backoff to kilkanaście minut zwłoki po to, żeby dojść
     * do wniosku znanego z pierwszej odpowiedzi.
     */
    @Test
    @DisplayName("Błąd trwały kończy zlecenie natychmiast, bez zużywania prób")
    void permanentFailure_shouldFailImmediately() {
        when(provider.translate(anyString(), any()))
                .thenThrow(new TranslationProviderException("PROVIDER_REJECTED", false, "400"));

        TranslationJob job = newJob("Ala ma kota");

        assertThat(worker.processBatch()).isZero();

        TranslationJob after = reload(job.getId());
        assertThat(after.getStatus()).isEqualTo(TranslationStatus.FAILED);
        assertThat(after.getAttempts())
                .as("poddajemy się po PIERWSZYM podejściu, mimo że limit prób jest wyższy")
                .isEqualTo(1);
        assertThat(properties.maxAttempts()).isGreaterThan(1);
        assertThat(after.getCompletedAt()).isNotNull();
    }

    @Test
    @DisplayName("Po wyczerpaniu prób zlecenie dostaje status FAILED")
    void exhaustedAttempts_shouldFail() {
        when(provider.translate(anyString(), any()))
                .thenThrow(new TranslationProviderException("PROVIDER_UNAVAILABLE", true, "503"));

        TranslationJob job = newJob("Ala ma kota");

        for (int attempt = 0; attempt < properties.maxAttempts(); attempt++) {
            makeReady();
            worker.processBatch();
        }

        TranslationJob after = reload(job.getId());
        assertThat(after.getStatus()).isEqualTo(TranslationStatus.FAILED);
        assertThat(after.getAttempts()).isEqualTo(properties.maxAttempts());
    }

    /**
     * Nieznany wyjątek (nie od dostawcy) traktujemy jak przejściowy. Fałszywe ponowienie
     * kosztuje znaki, fałszywe poddanie się kosztuje pracę użytkownika i jest nieodwracalne.
     */
    @Test
    @DisplayName("Nieznany błąd jest traktowany jak przejściowy")
    void unexpectedError_shouldBeTreatedAsRetryable() {
        when(provider.translate(anyString(), any()))
                .thenThrow(new IllegalStateException("coś pękło po naszej stronie"));

        TranslationJob job = newJob("Ala ma kota");

        worker.processBatch();

        assertThat(reload(job.getId()).getStatus()).isEqualTo(TranslationStatus.PENDING);
    }

    /**
     * Paczka ma lecieć RÓWNOLEGLE. Bariera na tylu uczestników, ile zleceń: przy wysyłce
     * szeregowej pierwsze wywołanie czeka na drugie, które nigdy nie nastąpi, więc test
     * PADA po timeoucie, zamiast tylko trwać dłużej. Bez tego "równolegle" byłoby
     * deklaracją bez pokrycia.
     */
    @Test
    @DisplayName("Zlecenia z paczki są tłumaczone równolegle")
    void batch_shouldBeTranslatedInParallel() {
        int size = properties.concurrency();
        CyclicBarrier barrier = new CyclicBarrier(size);

        when(provider.translate(anyString(), any())).thenAnswer(invocation -> {
            barrier.await(5, TimeUnit.SECONDS);
            return new TranslationResult("gotowe", "PL");
        });

        for (int i = 0; i < size; i++) {
            newJob("Tekst " + i);
        }

        assertThat(worker.processBatch())
                .as("wszystkie zlecenia z paczki miały się przetłumaczyć")
                .isEqualTo(size);
    }

    /**
     * Rezerwacja jest OKNEM CZASOWYM, nie statusem: zlecenie zabrane przez jedną instancję
     * jest niewidoczne dla drugiej, dopóki okno nie minie. To jest własność, na której stoi
     * bezpieczeństwo pracy kilku instancji naraz - bez niej dwie tłumaczyłyby to samo
     * zlecenie i płaciły za nie dwa razy.
     */
    @Test
    @DisplayName("Zarezerwowane zlecenie znika z widoku innej instancji")
    void claimedJob_shouldBeInvisibleToOtherInstance() {
        TranslationJob job = newJob("Ala ma kota");
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        // Instancja A: odczyt i rezerwacja w jednej transakcji, dokładnie jak w workerze
        tx.execute(status -> {
            Instant now = DbClock.now();
            List<TranslationJob> candidates = jobRepository.findClaimable(now, Limit.of(10));
            assertThat(candidates).extracting(TranslationJob::getId).containsExactly(job.getId());

            jobRepository.claim(List.of(job.getId()), now, now.plus(properties.claimTimeout()));
            return null;
        });

        // Instancja B, chwilę później
        List<TranslationJob> forOther = tx.execute(status ->
                jobRepository.findClaimable(DbClock.now(), Limit.of(10)));

        assertThat(forOther)
                .as("zlecenie zarezerwowane przez inną instancję nie może być widoczne")
                .isEmpty();

        assertThat(reload(job.getId()).getStatus())
                .as("status widoczny dla użytkownika ma pokazywać, że coś się dzieje")
                .isEqualTo(TranslationStatus.PROCESSING);
    }

    /**
     * Druga strona tej samej monety: zlecenie porzucone przez proces, który padł w trakcie,
     * MUSI wrócić do obiegu po upływie okna. Gdyby o możliwości wzięcia decydował status
     * PROCESSING, a nie next_attempt_at, takie zlecenie zostałoby w nim na zawsze.
     */
    @Test
    @DisplayName("Zlecenie porzucone w trakcie wraca po upływie rezerwacji")
    void abandonedJob_shouldReturnAfterClaimWindow() throws BrokenBarrierException {
        TranslationJob job = newJob("Ala ma kota");

        // Symulacja instancji, która zarezerwowała zlecenie i padła
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.execute(status -> {
            Instant now = DbClock.now();
            jobRepository.findClaimable(now, Limit.of(10));
            jobRepository.claim(List.of(job.getId()), now, now.plus(properties.claimTimeout()));
            return null;
        });

        // Okno rezerwacji mija
        jdbcTemplate.update("update translation_jobs set next_attempt_at = ? where id = ?",
                sql(Instant.now().minusSeconds(1)), job.getId());

        when(provider.translate(anyString(), any()))
                .thenReturn(new TranslationResult("gotowe", "PL"));

        assertThat(worker.processBatch())
                .as("po upływie rezerwacji zlecenie musi wrócić do obiegu")
                .isEqualTo(1);
        assertThat(reload(job.getId()).getStatus()).isEqualTo(TranslationStatus.DONE);
    }
}
