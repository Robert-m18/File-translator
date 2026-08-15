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
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Walidacja wgrywanego pliku.
 *
 * Sedno tej klasy to trzeci test: plik w Windows-1250 NIE wywala się przy zwykłym
 * new String(bytes, UTF_8) - daje tekst ze znakami zastępczymi w miejscu polskich liter.
 * Taka treść przeszłaby przez całą kolejkę, poleciała do dostawcy i wróciła do użytkownika
 * jako "tłumaczenie", bez jednego błędu po drodze. Dlatego dekoder w UploadedTextFile
 * jest ustawiony na REPORT, a nie na REPLACE.
 *
 * CZEGO TU NIE MA: przekroczonego rozmiaru pliku. Limit egzekwuje kontener serwletów przy
 * parsowaniu multiparta, a MockMvc żadnego kontenera nie ma - MockMultipartFile omija tę
 * ścieżkę niezależnie od rozmiaru. Test przechodziłby więc także przy wyłączonym limicie,
 * czyli nie sprawdzałby niczego. Sprawdzane ręcznie na docker compose.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TranslationUploadValidationTest {

    private static final String EMAIL = "walidacja@example.com";

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
        TranslationTestSupport.createUser(userRepository, passwordEncoder, EMAIL, "Walidacja");
        accessToken = TranslationTestSupport.login(mockMvc, EMAIL);
    }

    private MockMultipartFile file(String name, String content, Charset charset) {
        return new MockMultipartFile("file", name, "text/plain", content.getBytes(charset));
    }

    @Test
    @DisplayName("Poprawny plik .txt zostaje przyjęty jako zlecenie w kolejce")
    void validFile_shouldBeAccepted() throws Exception {
        mockMvc.perform(multipart("/translations")
                        .file(file("lista.txt", "Ala ma kota", StandardCharsets.UTF_8))
                        .param("targetLang", "EN_GB")
                        .cookie(accessToken)
                        .with(csrf()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.originalFilename").value("lista.txt"))
                .andExpect(jsonPath("$.targetLang").value("EN_GB"))
                .andExpect(jsonPath("$.charCount").value("Ala ma kota".length()));

        assertThat(jobRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("Pusty plik jest odrzucany")
    void emptyFile_shouldBeRejected() throws Exception {
        mockMvc.perform(multipart("/translations")
                        .file(file("pusty.txt", "", StandardCharsets.UTF_8))
                        .param("targetLang", "EN_GB")
                        .cookie(accessToken)
                        .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EMPTY_FILE"));

        assertThat(jobRepository.count()).isZero();
    }

    @Test
    @DisplayName("Plik z samymi białymi znakami jest odrzucany - nie ma czego tłumaczyć")
    void blankFile_shouldBeRejected() throws Exception {
        mockMvc.perform(multipart("/translations")
                        .file(file("spacje.txt", "   \n\t\n  ", StandardCharsets.UTF_8))
                        .param("targetLang", "EN_GB")
                        .cookie(accessToken)
                        .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EMPTY_FILE"));
    }

    /**
     * ZMIANA KONTRAKTU wobec wersji sprzed formatów binarnych: decyduje ZAWARTOŚĆ, a nie
     * rozszerzenie. Plik nazwany "umowa.pdf", w którym leży zwykły tekst, PDF-em nie jest -
     * i jest przyjmowany jako tekst, bo taki właśnie jest.
     *
     * Wcześniej ten sam przypadek kończył się odmową, i to była słabsza reguła: opierała się
     * na nazwie, którą ustawia klient. Odwrotny kierunek - plik .txt z prawdziwym PDF-em
     * w środku - był wtedy PRZEPUSZCZANY i szedł do dostawcy jako tekst.
     */
    @Test
    @DisplayName("O typie decyduje zawartość, nie rozszerzenie w nazwie")
    void textContentWithPdfName_shouldBeAcceptedAsText() throws Exception {
        mockMvc.perform(multipart("/translations")
                        .file(new MockMultipartFile("file", "umowa.pdf", "application/pdf",
                                "zwykły tekst".getBytes(StandardCharsets.UTF_8)))
                        .param("targetLang", "EN_GB")
                        .cookie(accessToken)
                        .with(csrf()))
                .andExpect(status().isAccepted());
    }

    /**
     * Prawdziwy PDF rozpoznawany po sygnaturze "%PDF-", niezależnie od tego, jak nazwano plik.
     * To jest ta strona reguły, której poprzednia wersja nie miała w ogóle.
     */
    @Test
    @DisplayName("Sygnatura PDF-a decyduje nawet przy nazwie .txt")
    void pdfContentWithTxtName_shouldBeAcceptedAsDocument() throws Exception {
        mockMvc.perform(multipart("/translations")
                        .file(new MockMultipartFile("file", "notatka.txt", "text/plain", pdfBytes("tresc")))
                        .param("targetLang", "EN_GB")
                        .cookie(accessToken)
                        .with(csrf()))
                .andExpect(status().isAccepted());
    }

    /**
     * Archiwum ZIP, które nie nazywa się .xlsx, jest odrzucane - i to jest jedyne miejsce,
     * w którym o typie współdecyduje nazwa.
     *
     * Powód jest wprost zapisany w FileType: XLSX, DOCX i PPTX mają IDENTYCZNĄ sygnaturę, bo
     * wszystkie są archiwami ZIP. Odróżnienie ich wymagałoby otwarcia archiwum, czyli
     * dokładnie tego, czego nie robimy, żeby nie wpuścić sobie bomb dekompresyjnych.
     */
    @Test
    @DisplayName("Archiwum ZIP bez rozszerzenia .xlsx jest odrzucane")
    void zipThatIsNotXlsx_shouldBeRejected() throws Exception {
        byte[] zip = {'P', 'K', 0x03, 0x04, 0x14, 0x00, 0x00, 0x00};

        mockMvc.perform(multipart("/translations")
                        .file(new MockMultipartFile("file", "archiwum.zip", "application/zip", zip))
                        .param("targetLang", "EN_GB")
                        .cookie(accessToken)
                        .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_FILE_TYPE"));
    }

    /**
     * Limit rozmiaru jest PER FORMAT, nie wspólny: dla tekstu wyprowadza się go z ekonomii
     * dostawcy (bajty to praktycznie znaki), dla binariów z chęci ograniczenia szkody
     * z jednego pliku. Wspólny limit musiałby przepuścić największy z nich, więc plik .txt
     * o rozmiarze dopuszczalnym dla PDF-a przeszedłby i wyjadł miesięczny budżet konta.
     */
    @Test
    @DisplayName("Plik tekstowy powyżej limitu SWOJEGO formatu jest odrzucany")
    void textFileOverItsOwnLimit_shouldBeRejected() throws Exception {
        byte[] tooBig = "a".repeat(300 * 1024).getBytes(StandardCharsets.UTF_8);

        mockMvc.perform(multipart("/translations")
                        .file(new MockMultipartFile("file", "duzy.txt", "text/plain", tooBig))
                        .param("targetLang", "EN_GB")
                        .cookie(accessToken)
                        .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FILE_TOO_LARGE_FOR_TYPE"));
    }

    /** Minimalne bajty wyglądające jak PDF - liczy się sygnatura, bo jej szuka FileType. */
    private byte[] pdfBytes(String payload) {
        byte[] body = payload.getBytes(StandardCharsets.UTF_8);
        byte[] pdf = new byte[5 + body.length];
        System.arraycopy("%PDF-".getBytes(StandardCharsets.UTF_8), 0, pdf, 0, 5);
        System.arraycopy(body, 0, pdf, 5, body.length);
        return pdf;
    }

    /**
     * Rozszerzenie decyduje, a nagłówek Content-Type nie - bo ten drugi ustawia klient
     * i można w nim napisać dowolną rzecz. Tu przychodzi application/pdf przy pliku .txt:
     * treść jest poprawnym tekstem, więc żądanie ma przejść.
     */
    @Test
    @DisplayName("Content-Type od klienta nie decyduje o odrzuceniu")
    void contentTypeHeader_shouldNotDecide() throws Exception {
        mockMvc.perform(multipart("/translations")
                        .file(new MockMultipartFile("file", "notatka.txt", "application/pdf",
                                "Zwykły tekst".getBytes(StandardCharsets.UTF_8)))
                        .param("targetLang", "DE")
                        .cookie(accessToken)
                        .with(csrf()))
                .andExpect(status().isAccepted());
    }

    @Test
    @DisplayName("Plik w Windows-1250 z polskimi znakami jest odrzucany, a nie cicho uszkadzany")
    void nonUtf8File_shouldBeRejected() throws Exception {
        // Bajty polskich znaków w Windows-1250 są niepoprawną sekwencją UTF-8.
        // Zwykłe new String(bytes, UTF_8) zamieniłoby je na znaki zastępcze BEZ BŁĘDU.
        byte[] cp1250 = "Zażółć gęślą jaźń".getBytes(Charset.forName("windows-1250"));

        mockMvc.perform(multipart("/translations")
                        .file(new MockMultipartFile("file", "polskie.txt", "text/plain", cp1250))
                        .param("targetLang", "EN_GB")
                        .cookie(accessToken)
                        .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_FILE_ENCODING"));

        assertThat(jobRepository.count()).isZero();
    }

    @Test
    @DisplayName("Nieobsługiwany język docelowy zwraca listę dozwolonych wartości")
    void unknownTargetLanguage_shouldListAllowedValues() throws Exception {
        mockMvc.perform(multipart("/translations")
                        .file(file("lista.txt", "Ala ma kota", StandardCharsets.UTF_8))
                        .param("targetLang", "KLINGON")
                        .cookie(accessToken)
                        .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_TARGET_LANGUAGE"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("EN_GB")));
    }

    /**
     * Nazwa pliku wraca później w nagłówku Content-Disposition, więc ścieżka w niej
     * nie może przetrwać zapisu.
     */
    @Test
    @DisplayName("Ścieżka w nazwie pliku jest obcinana do samej nazwy")
    void pathInFilename_shouldBeStripped() throws Exception {
        mockMvc.perform(multipart("/translations")
                        .file(file("../../etc/passwd.txt", "Ala ma kota", StandardCharsets.UTF_8))
                        .param("targetLang", "EN_GB")
                        .cookie(accessToken)
                        .with(csrf()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.originalFilename").value("passwd.txt"));
    }

    @Test
    @DisplayName("Niezalogowany nie może zlecić tłumaczenia")
    void anonymous_shouldGet401() throws Exception {
        mockMvc.perform(multipart("/translations")
                        .file(file("lista.txt", "Ala ma kota", StandardCharsets.UTF_8))
                        .param("targetLang", "EN_GB")
                        .with(csrf()))
                .andExpect(status().isUnauthorized());

        assertThat(jobRepository.count()).isZero();
    }
}
