package com.example.robert.translation;

import com.example.robert.translation.model.TranslationStatus;
import com.example.robert.translation.provider.TranslationProvider;
import com.example.robert.translation.provider.TranslationProviderException;
import com.example.robert.translation.provider.TranslationResult;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Deduplikacja po odcisku treści: ten sam plik nie jest tłumaczony - ani płacony - dwa razy.
 *
 * Miarą we WSZYSTKICH testach jest LICZBA WYWOŁAŃ DOSTAWCY, a nie status zlecenia. Status
 * wychodzi DONE w obie strony, więc sprawdzanie go nie odróżniłoby działającej deduplikacji
 * od nieistniejącej - a to właśnie ta różnica jest tu jedyną rzeczą wartą pilnowania.
 *
 * Limit dobowy obniżony przez @TestPropertySource, żeby dało się sprawdzić, że trafienie
 * w cache go nie zjada - przy limicie z profilu testowego (milion znaków) trzeba by budować
 * pliki, których nikt nie chce oglądać w teście.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "app.translation.daily-char-limit=100")
class TranslationDedupTest {

    private static final String EMAIL = "dedup@example.com";
    private static final String OTHER_EMAIL = "dedup-inny@example.com";
    private static final String CONTENT = "Ala ma kota";

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
        TranslationTestSupport.createUser(userRepository, passwordEncoder, EMAIL, "Dedup");
        accessToken = TranslationTestSupport.login(mockMvc, EMAIL);

        when(provider.translate(anyString(), any()))
                .thenReturn(new TranslationResult("gotowe tlumaczenie", "PL"));
    }

    private MvcResult submit(Cookie cookie, String content, String targetLang) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "plik.txt", "text/plain",
                content.getBytes(StandardCharsets.UTF_8));

        return mockMvc.perform(multipart("/translations")
                        .file(file)
                        .param("targetLang", targetLang)
                        .cookie(cookie)
                        .with(csrf()))
                .andReturn();
    }

    /** Zlecenie przyjęte i od razu wykonane - zwraca id, żeby dało się sprawdzić wynik. */
    private long submitAndProcess(Cookie cookie, String content, String targetLang) throws Exception {
        MvcResult result = submit(cookie, content, targetLang);
        assertThat(result.getResponse().getStatus()).isEqualTo(202);

        long id = ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
        worker.processBatch();
        return id;
    }

    /**
     * Treść wyniku leży w magazynie obiektowym, a wiersz trzyma sam klucz - stąd dwa kroki
     * zamiast jednego gettera. Ten odczyt jest zarazem sprawdzeniem, że trafienie w cache
     * SKOPIOWAŁO plik pod klucz nowego zlecenia, a nie tylko przepisało wskazanie: gdyby
     * kopiowanie wypadło, klucz wskazywałby na nieistniejący obiekt i odczyt by się wywalił.
     */
    private String resultOf(long jobId) {
        String key = jobRepository.findById(jobId).orElseThrow().getResultObjectKey();
        return new String(objectStore.read(key), StandardCharsets.UTF_8);
    }

    /**
     * SEDNO całej funkcji. Drugie zlecenie tej samej treści kończy się identycznym wynikiem,
     * a dostawca jest wołany RAZ - czyli za drugie nie zapłacono ani znaku.
     */
    @Test
    @DisplayName("Ta sama treść drugi raz: wynik z cache'a, dostawca wołany raz")
    void secondSubmit_shouldReuseResultWithoutCallingProvider() throws Exception {
        long first = submitAndProcess(accessToken, CONTENT, "EN_GB");
        long second = submitAndProcess(accessToken, CONTENT, "EN_GB");

        verify(provider, times(1)).translate(anyString(), any());

        assertThat(jobRepository.findById(second).orElseThrow().getStatus())
                .isEqualTo(TranslationStatus.DONE);
        assertThat(resultOf(second))
                .as("zlecenie z cache'a ma dać dokładnie ten sam wynik co oryginał")
                .isEqualTo(resultOf(first));
    }

    /**
     * Zasięg cache'a jest PER UŻYTKOWNIK i ten test to przypina. Wariant globalny oszczędzałby
     * więcej, ale zostawiałby kanał boczny: natychmiastowy DONE zdradzałby, że ktoś inny ma
     * dokładnie ten plik. Bez tego testu cichy przeskok na zasięg globalny przeszedłby
     * niezauważony, bo wszystkie pozostałe testy byłyby wtedy dalej zielone.
     */
    @Test
    @DisplayName("Cudze gotowe tłumaczenie nie jest trafieniem")
    void otherUser_shouldNotHitCache() throws Exception {
        submitAndProcess(accessToken, CONTENT, "EN_GB");

        TranslationTestSupport.createUser(userRepository, passwordEncoder, OTHER_EMAIL, "Inny");
        Cookie other = TranslationTestSupport.login(mockMvc, OTHER_EMAIL);

        submitAndProcess(other, CONTENT, "EN_GB");

        verify(provider, times(2)).translate(anyString(), any());
    }

    /**
     * NAJWAŻNIEJSZY test z całego zestawu, bo pilnuje jedynego scenariusza, w którym
     * deduplikacja oddaje wynik BŁĘDNY, a nie tylko szybki: gotowe zlecenie wykonane przez
     * atrapę (ECHO) nie może zaspokoić zlecenia kierowanego do prawdziwego dostawcy. Treść
     * i język docelowy się zgadzają, więc bez kolumny provider wiersz wyglądałby na poprawne
     * trafienie i użytkownik dostałby wyjście atrapy podpisane jako tłumaczenie.
     *
     * Odwrócone względem konfiguracji: aplikacja stoi na ECHO, więc gotowy wiersz przestawiamy
     * na DEEPL. Kierunek jest bez znaczenia - liczy się to, że różnica dostawcy blokuje trafienie.
     */
    @Test
    @DisplayName("Wynik innego dostawcy nie jest trafieniem")
    void differentProvider_shouldNotHitCache() throws Exception {
        long first = submitAndProcess(accessToken, CONTENT, "EN_GB");

        jdbcTemplate.update("update translation_jobs set provider = 'DEEPL' where id = ?", first);

        submitAndProcess(accessToken, CONTENT, "EN_GB");

        verify(provider, times(2)).translate(anyString(), any());
    }

    @Test
    @DisplayName("Ta sama treść w innym języku docelowym nie jest trafieniem")
    void differentTargetLang_shouldNotHitCache() throws Exception {
        submitAndProcess(accessToken, CONTENT, "EN_GB");
        submitAndProcess(accessToken, CONTENT, "DE");

        verify(provider, times(2)).translate(anyString(), any());
    }

    /**
     * Cache'em są WYŁĄCZNIE zlecenia zakończone powodzeniem. Zlecenie, które padło, nie ma
     * czego oddać - a gdyby liczyło się jako trafienie, jedna awaria dostawcy zamieniłaby się
     * w trwałe zatrucie: każda kolejna próba tej samej treści kończyłaby się natychmiast,
     * kopiując pustkę.
     */
    @Test
    @DisplayName("Zlecenie zakończone porażką nie służy za cache")
    void failedJob_shouldNotServeAsCache() throws Exception {
        // doThrow/doReturn, a NIE when(...): when() wywołuje metodę na atrapie, więc przy
        // przestawianiu stubu z rzucania z powrotem na wynik samo stubowanie rzuciłoby
        // wyjątkiem ustawionym linijkę wyżej.
        doThrow(new TranslationProviderException("PROVIDER_REJECTED", false, "400"))
                .when(provider).translate(anyString(), any());

        long failed = submitAndProcess(accessToken, CONTENT, "EN_GB");
        assertThat(jobRepository.findById(failed).orElseThrow().getStatus())
                .isEqualTo(TranslationStatus.FAILED);

        doReturn(new TranslationResult("gotowe tlumaczenie", "PL"))
                .when(provider).translate(anyString(), any());

        long second = submitAndProcess(accessToken, CONTENT, "EN_GB");

        verify(provider, times(2)).translate(anyString(), any());
        assertThat(jobRepository.findById(second).orElseThrow().getStatus())
                .isEqualTo(TranslationStatus.DONE);
    }

    /**
     * Dobowy limit znaków chroni konto U DOSTAWCY, a trafienie w cache nic tam nie kosztuje -
     * naliczanie go byłoby karą za operację, która jest darmowa.
     *
     * Liczby dobrane tak, żeby test padał bez tej gałęzi: limit 100, dwa razy po 60 znaków.
     * Drugie zlecenie mieści się WYŁĄCZNIE dlatego, że jest trafieniem; przy naliczaniu jak
     * dawniej wróciłoby 429.
     */
    @Test
    @DisplayName("Trafienie w cache nie zjada dobowego limitu znaków")
    void cacheHit_shouldNotConsumeDailyQuota() throws Exception {
        String content = "b".repeat(60);

        submitAndProcess(accessToken, content, "EN_GB");

        assertThat(submit(accessToken, content, "EN_GB").getResponse().getStatus())
                .as("drugie zlecenie tej samej treści nie może odbić się od limitu")
                .isEqualTo(202);
    }

    /**
     * DRUGA POŁOWA tego samego wymagania, i to ta, której brakowało: trafienie nie może
     * podnosić licznika także NASTĘPNYM zleceniom.
     *
     * Test wyżej sprawdzał wyłącznie, że samo zlecenie z cache'a nie odbija się od limitu -
     * i przechodził, mimo że limit liczył zużycie sumą charCount po wszystkich wierszach,
     * czyli doliczał też trafienia. Powtórka nie płaciła więc za siebie, ale zjadała budżet
     * naprawdę nowym plikom. Znalezione 2026-08-13 ręcznym przejściem na docker compose:
     * trzy wgrania tego samego pliku po 5 znaków dały zużycie 15 przy najwyżej 5 wydanych
     * u dostawcy.
     *
     * Liczby dobrane tak, żeby test padał przed naprawą: limit 100, plik 60 znaków wgrany
     * dwa razy (drugi raz z cache'a), potem NOWY plik na 30 znaków. Po naprawie zużycie to
     * 60, więc nowy plik się mieści; przy sumowaniu charCount byłoby 120 i wróciłoby 429.
     */
    @Test
    @DisplayName("Trafienie w cache nie zjada limitu następnym zleceniom")
    void cacheHit_shouldNotInflateQuotaForLaterFiles() throws Exception {
        String repeated = "b".repeat(60);

        submitAndProcess(accessToken, repeated, "EN_GB");
        long cachedJob = submitAndProcess(accessToken, repeated, "EN_GB");

        assertThat(jobRepository.findById(cachedJob).orElseThrow().getBilledChars())
                .as("zlecenie zaspokojone z cache'a nie wydało u dostawcy ani znaku")
                .isZero();

        assertThat(submit(accessToken, "e".repeat(30), "EN_GB").getResponse().getStatus())
                .as("nowy plik ma się zmieścić w limicie, bo trafienie nic z niego nie zabrało")
                .isEqualTo(202);
    }

    /**
     * Wartość zapisana przy przyjęciu zlecenia jest tylko PRZEWIDYWANIEM, a worker musi ją
     * skorygować. Tu gotowy wiersz znika między jednym a drugim (użytkownik go kasuje; tak
     * samo zadziałałaby retencja), więc zlecenie przyjęte jako darmowe jednak idzie do
     * dostawcy - i musi zostać naliczone.
     *
     * Bez korekty w markDone te znaki nie policzyłyby się nigdzie i dobowy limit dałoby się
     * obchodzić: wystarczyłoby zlecić plik, który już się ma, i skasować oryginał.
     */
    @Test
    @DisplayName("Trafienie, które przepadło przed obróbką, jest jednak naliczane")
    void vanishedCacheHit_shouldBeBilledAfterAll() throws Exception {
        String content = "f".repeat(60);

        long first = submitAndProcess(accessToken, content, "EN_GB");

        // Przyjęte jako trafienie - w tej chwili gotowy wynik jeszcze istnieje.
        MvcResult accepted = submit(accessToken, content, "EN_GB");
        assertThat(accepted.getResponse().getStatus()).isEqualTo(202);
        long second = ((Number) JsonPath.read(
                accepted.getResponse().getContentAsString(), "$.id")).longValue();

        jobRepository.deleteById(first);
        worker.processBatch();

        verify(provider, times(2)).translate(anyString(), any());
        assertThat(jobRepository.findById(second).orElseThrow().getBilledChars())
                .as("dostawca był wołany, więc znaki są wydane mimo przewidywania trafienia")
                .isEqualTo(60);
    }

    /**
     * Kontrola negatywna do testu wyżej: treść, której w cache'u NIE MA, dalej odbija się
     * od limitu. Bez tego "limit nie działa wcale" przechodziłoby tak samo jak "limit pomija
     * trafienia".
     */
    @Test
    @DisplayName("Nowa treść nadal odbija się od dobowego limitu")
    void newContent_shouldStillHitDailyQuota() throws Exception {
        submitAndProcess(accessToken, "c".repeat(60), "EN_GB");

        mockMvc.perform(multipart("/translations")
                        .file(new MockMultipartFile("file", "plik.txt", "text/plain",
                                "d".repeat(60).getBytes(StandardCharsets.UTF_8)))
                        .param("targetLang", "EN_GB")
                        .cookie(accessToken)
                        .with(csrf()))
                .andExpect(status().isTooManyRequests());
    }
}
