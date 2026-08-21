package com.example.filetranslator.translation.provider;

import com.example.filetranslator.translation.TranslationProperties;
import com.example.filetranslator.translation.model.TargetLanguage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withRawStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Adapter DeepL - bez sieci i bez klucza.
 *
 * TO JEST TEST, KTÓRY CZYNI TEN ADAPTER REALNYM. Bez niego kod rozmawiający z zewnętrznym
 * API jest sprawdzony dopiero przy pierwszym prawdziwym wywołaniu, czyli na produkcji,
 * i to wyłącznie na ścieżce szczęśliwej - odpowiedzi 429 czy 456 nie da się wywołać
 * na życzenie u dostawcy.
 *
 * Najważniejsza część to mapowanie kodów na flagę retryable. To ona decyduje, czy zlecenie
 * będzie ponawiane przez kilkanaście minut, czy zostanie zamknięte od razu - a pomyłka
 * w którąkolwiek stronę jest kosztowna: niepotrzebne ponawianie zużywa znaki i opóźnia
 * kolejkę, przedwczesne poddanie się kasuje pracę użytkownika.
 */
class DeepLTranslationProviderTest {

    private static final String BASE_URL = "https://api-free.deepl.example/v2";
    private static final String API_KEY = "klucz-testowy";

    private MockRestServiceServer server;
    private DeepLTranslationProvider provider;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl(BASE_URL)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "DeepL-Auth-Key " + API_KEY);

        server = MockRestServiceServer.bindTo(builder).build();
        provider = new DeepLTranslationProvider(builder.build(), properties(API_KEY));
    }

    private TranslationProperties properties(String apiKey) {
        return new TranslationProperties(
                true,
                TranslationProperties.Provider.DEEPL,
                50_000, 5, 2, 3,
                Duration.ofSeconds(10), Duration.ofMinutes(2),
                Duration.ofSeconds(5), Duration.ofDays(30),
                new TranslationProperties.DeepL(BASE_URL, apiKey,
                        Duration.ofSeconds(5), Duration.ofSeconds(30)));
    }

    private void respondWith(HttpStatus status) {
        server.expect(requestTo(BASE_URL + "/translate"))
                .andRespond(withStatus(status));
    }

    /**
     * Dla kodów spoza standardu HTTP. Konieczne dla 456: HttpStatus.valueOf(456) rzuca
     * "No matching constant" - i to jest dokładnie powód, dla którego kod produkcyjny
     * rozgałęzia się na HttpStatusCode, a nie na wyliczeniu HttpStatus.
     */
    private void respondWithRaw(int status) {
        server.expect(requestTo(BASE_URL + "/translate"))
                .andRespond(withRawStatus(status));
    }

    private TranslationProviderException translateExpectingFailure() {
        return (TranslationProviderException) org.assertj.core.api.Assertions
                .catchThrowable(() -> provider.translate("Ala ma kota", TargetLanguage.DE));
    }

    @Test
    @DisplayName("Wysyła tekst i język w formacie oczekiwanym przez DeepL")
    void translate_shouldSendExpectedRequest() {
        server.expect(requestTo(BASE_URL + "/translate"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                // Nagłówek autoryzacji ma format DeepL-Auth-Key, a NIE Bearer -
                // pomyłka kończy się odpowiedzią 403 wyglądającą jak zły klucz
                .andExpect(header(HttpHeaders.AUTHORIZATION, "DeepL-Auth-Key " + API_KEY))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                // text jest TABLICĄ, nawet dla jednego dokumentu
                .andExpect(jsonPath("$.text[0]").value("Ala ma kota"))
                // kod języka w formacie API (DE), nie nazwa stałej enuma
                .andExpect(jsonPath("$.target_lang").value("DE"))
                .andRespond(withSuccess("""
                        {"translations":[{"detected_source_language":"PL","text":"Anna hat eine Katze"}]}
                        """, MediaType.APPLICATION_JSON));

        TranslationResult result = provider.translate("Ala ma kota", TargetLanguage.DE);

        assertThat(result.translatedText()).isEqualTo("Anna hat eine Katze");
        assertThat(result.detectedSourceLanguage()).isEqualTo("PL");
        server.verify();
    }

    @Test
    @DisplayName("Kod EN_GB jedzie do API jako EN-GB")
    void targetLanguage_shouldUseApiCode() {
        server.expect(requestTo(BASE_URL + "/translate"))
                .andExpect(jsonPath("$.target_lang").value("EN-GB"))
                .andRespond(withSuccess("""
                        {"translations":[{"detected_source_language":"PL","text":"ok"}]}
                        """, MediaType.APPLICATION_JSON));

        provider.translate("Ala ma kota", TargetLanguage.EN_GB);
        server.verify();
    }

    @Test
    @DisplayName("429 to porażka PRZEJŚCIOWA - warto ponowić")
    void tooManyRequests_shouldBeRetryable() {
        respondWith(HttpStatus.TOO_MANY_REQUESTS);

        TranslationProviderException ex = translateExpectingFailure();

        assertThat(ex.isRetryable()).isTrue();
        assertThat(ex.getCode()).isEqualTo("TRANSLATION_PROVIDER_THROTTLED");
    }

    @Test
    @DisplayName("Awaria serwera dostawcy to porażka PRZEJŚCIOWA")
    void serverError_shouldBeRetryable() {
        server.expect(requestTo(BASE_URL + "/translate")).andRespond(withServerError());

        TranslationProviderException ex = translateExpectingFailure();

        assertThat(ex.isRetryable()).isTrue();
        assertThat(ex.getCode()).isEqualTo("TRANSLATION_PROVIDER_UNAVAILABLE");
    }

    /**
     * 456 to niestandardowy kod DeepL: wyczerpany limit znaków CAŁEGO konta. Ponawianie
     * nic tu nie da aż do odnowienia limitu, a każda próba to kolejne żądanie do dostawcy,
     * który już odmówił.
     */
    @Test
    @DisplayName("456 (wyczerpany limit konta) to porażka TRWAŁA")
    void quotaExceeded_shouldNotBeRetryable() {
        respondWithRaw(456);

        TranslationProviderException ex = translateExpectingFailure();

        assertThat(ex.isRetryable()).isFalse();
        assertThat(ex.getCode()).isEqualTo("TRANSLATION_QUOTA_EXCEEDED");
    }

    /**
     * Zły klucz nie naprawi się sam. Ponawianie oznaczałoby, że każde zlecenie w systemie
     * przechodzi pełny backoff, zanim zostanie odrzucone - przy błędzie konfiguracji
     * dotyczącym wszystkich zleceń naraz.
     */
    @Test
    @DisplayName("403 (zły klucz) to porażka TRWAŁA")
    void forbidden_shouldNotBeRetryable() {
        respondWith(HttpStatus.FORBIDDEN);

        TranslationProviderException ex = translateExpectingFailure();

        assertThat(ex.isRetryable()).isFalse();
        assertThat(ex.getCode()).isEqualTo("TRANSLATION_PROVIDER_REJECTED");
    }

    @Test
    @DisplayName("400 (odrzucone żądanie) to porażka TRWAŁA")
    void badRequest_shouldNotBeRetryable() {
        respondWith(HttpStatus.BAD_REQUEST);

        TranslationProviderException ex = translateExpectingFailure();

        assertThat(ex.isRetryable()).isFalse();
        assertThat(ex.getCode()).isEqualTo("TRANSLATION_PROVIDER_REJECTED");
    }

    /**
     * Odpowiedź 200 bez tłumaczenia to rozjazd kontraktu, a nie awaria - kolejne identyczne
     * żądanie da identyczny wynik, więc ponawianie jest samą zwłoką. Bez tej gałęzi worker
     * dostałby NullPointerException i potraktował go jako błąd przejściowy.
     */
    @Test
    @DisplayName("Odpowiedź 200 bez tłumaczenia nie jest ponawiana")
    void emptyTranslations_shouldNotBeRetryable() {
        server.expect(requestTo(BASE_URL + "/translate"))
                .andRespond(withSuccess("{\"translations\":[]}", MediaType.APPLICATION_JSON));

        TranslationProviderException ex = translateExpectingFailure();

        assertThat(ex.isRetryable()).isFalse();
        assertThat(ex.getCode()).isEqualTo("TRANSLATION_PROVIDER_REJECTED");
    }

    /**
     * Zatrzymanie startu przy brakującym kluczu. Sprawdzany jest też literał symbolu zastępczego, bo nieustawiona
     * zmienna środowiskowa binduje się właśnie tak - @ConfigurationProperties nie zgłasza
     * nierozwiązanego placeholdera (udokumentowana pułapka AdminProperties).
     */
    @Test
    @DisplayName("Brak klucza wywala start, nie pierwsze tłumaczenie")
    void missingApiKey_shouldFailFast() {
        RestClient client = RestClient.builder().baseUrl(BASE_URL).build();

        assertThatThrownBy(() -> new DeepLTranslationProvider(client, properties("")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("api-key");

        assertThatThrownBy(() -> new DeepLTranslationProvider(client, properties("${DEEPL_API_KEY}")))
                .as("nierozwiązany placeholder binduje się jako zwykły tekst i musi zostać wykryty")
                .isInstanceOf(IllegalStateException.class);
    }

    /** Komunikat błędu trafia do logów i do kolumny last_error - klucz nie ma prawa tam być. */
    @Test
    @DisplayName("Komunikat wyjątku nigdy nie zawiera klucza API")
    void exceptionMessage_shouldNeverLeakApiKey() {
        RestClient client = RestClient.builder().baseUrl(BASE_URL).build();

        assertThatThrownBy(() -> new DeepLTranslationProvider(client, properties("")))
                .hasMessageNotContaining(API_KEY);

        respondWith(HttpStatus.FORBIDDEN);
        assertThat(translateExpectingFailure().getMessage()).doesNotContain(API_KEY);
    }
}
