/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.robert.translation.provider;

import com.example.robert.translation.TranslationProperties;
import com.example.robert.translation.model.TargetLanguage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Tłumaczenie przez DeepL API.
 *
 * Włączane przez app.translation.provider=deepl. Bez klucza nie da się tego uruchomić i to
 * jest zamierzone - patrz konstruktor.
 *
 * CAŁA TRUDNOŚĆ TEJ KLASY SIEDZI W MAPOWANIU BŁĘDÓW, nie w wywołaniu HTTP. Worker musi
 * wiedzieć tylko jedno: czy ponawiać. Rozróżnienie robi się tutaj, bo tylko tutaj wiadomo,
 * co znaczą kody dostawcy:
 *
 *   429            - za dużo żądań naraz; minie samo               -> ponawiaj
 *   5xx            - awaria po stronie dostawcy; zwykle minie      -> ponawiaj
 *   timeout / IO   - sieć albo zawieszony serwer                   -> ponawiaj
 *   456            - wyczerpany limit znaków KONTA (nie użytkownika) -> NIE ponawiaj
 *   403            - nieprawidłowy klucz albo zły host (darmowy vs płatny) -> NIE ponawiaj
 *   400            - żądanie odrzucone (np. nieobsługiwany język)  -> NIE ponawiaj
 *
 * Trzy ostatnie wrócą identycznie za każdym razem, więc ponawianie ich to wyłącznie zwłoka.
 * Dwa z nich (456 i 403) to w dodatku sprawy dla CZŁOWIEKA, nie dla automatu - stąd log
 * na poziomie ERROR: cisza w tym miejscu oznacza usługę, która przestała działać dla
 * wszystkich, a wygląda jak brak ruchu.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.translation", name = "provider", havingValue = "deepl")
public class DeepLTranslationProvider implements TranslationProvider {

    /** Wyczerpany limit znaków konta u dostawcy - kod niestandardowy, specyficzny dla DeepL. */
    private static final int QUOTA_EXCEEDED = 456;

    private final RestClient restClient;

    public DeepLTranslationProvider(RestClient deepLRestClient, TranslationProperties properties) {
        String apiKey = properties.deepl().apiKey();

        /*
         * Sprawdzenie klucza siedzi TUTAJ, a nie w konfiguracji, bo tylko tutaj wiadomo, że
         * dostawca został w ogóle wybrany - wymuszanie klucza na wszystkich uruchomieniach
         * zablokowałoby profil testowy i deva na atrapie.
         *
         * Idiom "${DEEPL_API_KEY}" bez wartości domyślnej NIE zadziała jako fail-fast: binding
         * @ConfigurationProperties idzie przez PropertySourcesPlaceholdersResolver z włączonym
         * ignoreUnresolvablePlaceholders, więc nieustawiona zmienna wpisuje do pola literał
         * "${DEEPL_API_KEY}" bez ostrzeżenia. Ta sama pułapka co przy AdminProperties.
         *
         * Komunikat NIE MOŻE zawierać wartości klucza - ten wyjątek ląduje w konsoli
         * i w kolektorze logów.
         */
        if (apiKey == null || apiKey.isBlank() || apiKey.startsWith("${")) {
            throw new IllegalStateException(
                    "app.translation.provider=deepl wymaga ustawienia app.translation.deepl.api-key "
                            + "(zmienna środowiskowa DEEPL_API_KEY)");
        }

        this.restClient = deepLRestClient;
        log.info("Aktywny dostawca tłumaczenia: DeepL ({})", properties.deepl().apiUrl());
    }

    @Override
    public TranslationResult translate(String text, TargetLanguage target) {
        DeepLResponse response;
        try {
            response = restClient.post()
                    .uri("/translate")
                    .contentType(MediaType.APPLICATION_JSON)
                    // Tablica tekstów, bo tego oczekuje API - wysyłamy jeden element.
                    // Dzielenie długich plików na części byłoby osobną decyzją; przy limicie
                    // 256 KB na plik nie jest potrzebne.
                    .body(Map.of(
                            "text", List.of(text),
                            "target_lang", target.apiCode()))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, httpResponse) -> {
                        throw mapError(httpResponse.getStatusCode());
                    })
                    .body(DeepLResponse.class);

        } catch (ResourceAccessException e) {
            // Timeout połączenia albo odczytu, brak trasy do hosta - zawsze przejściowe.
            throw new TranslationProviderException("TRANSLATION_PROVIDER_UNAVAILABLE", true,
                    "Dostawca tłumaczenia nie odpowiedział", e);

        } catch (TranslationProviderException e) {
            throw e;

        } catch (RestClientException e) {
            // Odpowiedź, której nie dało się przetworzyć. Traktujemy jak przejściową:
            // dostawcy zdarza się oddać stronę błędu proxy zamiast JSON-a.
            throw new TranslationProviderException("TRANSLATION_PROVIDER_UNAVAILABLE", true,
                    "Nieoczekiwana odpowiedź dostawcy tłumaczenia", e);
        }

        if (response == null || response.translations() == null || response.translations().isEmpty()) {
            // Odpowiedź 200 bez tłumaczenia. Nie ponawiamy: to nie jest awaria, tylko rozjazd
            // kontraktu, a kolejne identyczne żądanie da identyczny wynik.
            throw new TranslationProviderException("TRANSLATION_PROVIDER_REJECTED", false,
                    "Dostawca zwrócił odpowiedź bez tłumaczenia");
        }

        DeepLTranslation translation = response.translations().get(0);
        return new TranslationResult(translation.text(), translation.detectedSourceLanguage());
    }

    /* ---------------------------------------------------------------------------------
     * Dokumenty: POST /v2/document (wgranie) -> POST /v2/document/{id} (status)
     * -> POST /v2/document/{id}/result (pobranie). Wszystkie trzy metodą POST, tak jak
     * opisuje dokumentacja DeepL - także sprawdzenie statusu, mimo że nic nie zmienia.
     * --------------------------------------------------------------------------------- */

    @Override
    public DocumentHandle uploadDocument(byte[] content, String filename, TargetLanguage target) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        // Nazwa pliku jedzie w części multiparta, bo to PO NIEJ dostawca rozpoznaje format -
        // bez niej odrzuca żądanie, nie wiedząc, czy dostał PDF-a, czy arkusz.
        form.add("file", new NamedByteArrayResource(content, filename));
        form.add("target_lang", target.apiCode());

        DocumentUpload upload = call(() -> restClient.post()
                .uri("/document")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(form)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, response) -> {
                    throw mapError(response.getStatusCode());
                })
                .body(DocumentUpload.class));

        if (upload == null || upload.document_id() == null || upload.document_key() == null) {
            throw new TranslationProviderException("TRANSLATION_PROVIDER_REJECTED", false,
                    "Dostawca przyjął dokument, ale nie zwrócił uchwytu do niego");
        }
        return new DocumentHandle(upload.document_id(), upload.document_key());
    }

    @Override
    public DocumentStatus checkDocument(DocumentHandle handle) {
        DocumentStatusResponse response = call(() -> restClient.post()
                .uri("/document/{id}", handle.documentId())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("document_key", handle.documentKey()))
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, httpResponse) -> {
                    // 404 przy sprawdzaniu statusu znaczy, że dokumentu już nie ma - został
                    // pobrany albo wygasł. To nie jest awaria, tylko sygnał "zacznij od nowa".
                    if (httpResponse.getStatusCode().value() == 404) {
                        throw new DocumentUnavailableException("Dokument nie istnieje u dostawcy");
                    }
                    throw mapError(httpResponse.getStatusCode());
                })
                .body(DocumentStatusResponse.class));

        if (response == null || response.status() == null) {
            throw new TranslationProviderException("TRANSLATION_PROVIDER_REJECTED", false,
                    "Dostawca nie zwrócił statusu dokumentu");
        }
        return response.toStatus();
    }

    @Override
    public byte[] downloadDocument(DocumentHandle handle) {
        return call(() -> restClient.post()
                .uri("/document/{id}/result", handle.documentId())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("document_key", handle.documentKey()))
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, response) -> {
                    /*
                     * 503 przy pobieraniu ma u DeepL DWA znaczenia naraz: "jeszcze się tłumaczy"
                     * ORAZ "już pobrany, więc skasowany". Rozróżnić ich z samej odpowiedzi nie
                     * sposób, ale wołający i tak schodzi tu dopiero po statusie DONE, więc
                     * pierwsze znaczenie odpada - zostaje drugie. 404 to ten sam przypadek.
                     */
                    int code = response.getStatusCode().value();
                    if (code == 404 || code == 503) {
                        throw new DocumentUnavailableException(
                                "Przetłumaczonego dokumentu nie ma już u dostawcy - da się go pobrać tylko raz");
                    }
                    throw mapError(response.getStatusCode());
                })
                .body(byte[].class));
    }

    /**
     * Wspólna obsługa awarii transportu dla wszystkich wywołań - identyczna jak w translate.
     * Wydzielona, bo powtórzona cztery razy rozjechałaby się przy pierwszej poprawce.
     */
    private <T> T call(Supplier<T> request) {
        try {
            return request.get();
        } catch (ResourceAccessException e) {
            throw new TranslationProviderException("TRANSLATION_PROVIDER_UNAVAILABLE", true,
                    "Dostawca tłumaczenia nie odpowiedział", e);
        } catch (TranslationProviderException e) {
            throw e;
        } catch (RestClientException e) {
            throw new TranslationProviderException("TRANSLATION_PROVIDER_UNAVAILABLE", true,
                    "Nieoczekiwana odpowiedź dostawcy tłumaczenia", e);
        }
    }

    private TranslationProviderException mapError(HttpStatusCode status) {
        int code = status.value();

        if (code == QUOTA_EXCEEDED) {
            log.error("Wyczerpany limit znaków konta u dostawcy tłumaczenia (HTTP 456) - "
                    + "tłumaczenia nie będą działać do odnowienia limitu");
            return new TranslationProviderException("TRANSLATION_QUOTA_EXCEEDED", false,
                    "Wyczerpany limit znaków u dostawcy tłumaczenia");
        }
        if (code == 429) {
            return new TranslationProviderException("TRANSLATION_PROVIDER_THROTTLED", true,
                    "Dostawca tłumaczenia ogranicza liczbę żądań");
        }
        if (status.is5xxServerError()) {
            return new TranslationProviderException("TRANSLATION_PROVIDER_UNAVAILABLE", true,
                    "Awaria po stronie dostawcy tłumaczenia (HTTP " + code + ")");
        }
        if (code == 401 || code == 403) {
            // Bez treści odpowiedzi w komunikacie: przy błędzie uwierzytelnienia bywa w niej
            // echo nagłówka, a tam siedzi klucz API.
            log.error("Dostawca tłumaczenia odrzucił uwierzytelnienie (HTTP {}) - sprawdź klucz API "
                    + "oraz to, czy adres pasuje do rodzaju konta (darmowe vs płatne)", code);
            return new TranslationProviderException("TRANSLATION_PROVIDER_REJECTED", false,
                    "Dostawca tłumaczenia odrzucił uwierzytelnienie");
        }
        return new TranslationProviderException("TRANSLATION_PROVIDER_REJECTED", false,
                "Dostawca tłumaczenia odrzucił żądanie (HTTP " + code + ")");
    }

    /**
     * Kształt odpowiedzi DeepL. Rekordy zamiast mapy, żeby rozjazd kontraktu wychodził przy
     * deserializacji, a nie przy pierwszym get() zwracającym null.
     */
    record DeepLResponse(List<DeepLTranslation> translations) {
    }

    record DeepLTranslation(String detected_source_language, String text) {

        /** Nazwa w JSON-ie jest z podkreśleniami; ta metoda daje czytelną nazwę w kodzie. */
        String detectedSourceLanguage() {
            return detected_source_language;
        }
    }

    /** Odpowiedź na wgranie dokumentu. */
    record DocumentUpload(String document_id, String document_key) {
    }

    /**
     * Odpowiedź na sprawdzenie statusu. seconds_remaining świadomie pomijamy: worker odpytuje
     * w stałym rytmie i nie ma co zrobić z prognozą dostawcy poza wpisaniem jej do logu.
     */
    record DocumentStatusResponse(String status, Integer billed_characters, String error_message) {

        DocumentStatus toStatus() {
            DocumentStatus.State state = switch (status) {
                case "queued" -> DocumentStatus.State.QUEUED;
                case "translating" -> DocumentStatus.State.TRANSLATING;
                case "done" -> DocumentStatus.State.DONE;
                case "error" -> DocumentStatus.State.ERROR;
                // Nieznany status traktujemy jak błąd, a nie jak "jeszcze trwa": odpytywanie
                // w nieskończoność o stan, którego nie rozumiemy, zamieniłoby rozjazd kontraktu
                // w zlecenie wiszące na zawsze.
                default -> DocumentStatus.State.ERROR;
            };
            String message = state == DocumentStatus.State.ERROR && error_message == null
                    ? "Dostawca zwrócił nieznany status dokumentu: " + status
                    : error_message;
            return new DocumentStatus(state, billed_characters, message);
        }
    }

    /**
     * Zasób z JAWNĄ nazwą pliku.
     *
     * ByteArrayResource domyślnie nie ma nazwy, więc Spring buduje część multiparta bez
     * filename - a DeepL rozpoznaje format właśnie po nim i odrzuca takie żądanie. Objaw jest
     * mylący: błąd wygląda na problem z zawartością dokumentu, a nie z nagłówkiem części.
     */
    static class NamedByteArrayResource extends ByteArrayResource {

        private final String filename;

        NamedByteArrayResource(byte[] content, String filename) {
            super(content);
            this.filename = filename;
        }

        @Override
        public String getFilename() {
            return filename;
        }
    }
}
