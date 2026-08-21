/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.filetranslator.translation.provider;

import com.example.filetranslator.translation.TranslationProperties;
import com.example.filetranslator.translation.model.TargetLanguage;
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
 * Implementacja portu tłumaczenia oparta na API DeepL. Aktywowana przez konfigurację dostawcy.
 *
 * Zasadnicza część tej klasy to mapowanie błędów, a nie samo wywołanie HTTP. Wykonawca kolejki
 * musi wiedzieć wyłącznie jedno: czy ponawiać. Rozróżnienie powstaje tutaj, bo tylko tutaj wiadomo,
 * co znaczą kody dostawcy:
 *
 *   429            - zbyt wiele żądań naraz, stan przejściowy            -> ponawiać
 *   5xx            - awaria po stronie dostawcy, zwykle przejściowa      -> ponawiać
 *   timeout, błąd sieci                                                  -> ponawiać
 *   456            - wyczerpany limit znaków całego konta                -> nie ponawiać
 *   401, 403       - nieprawidłowy klucz albo adres niepasujący do konta -> nie ponawiać
 *   400            - żądanie odrzucone, np. nieobsługiwany język         -> nie ponawiać
 *
 * Trzy ostatnie przypadki dadzą identyczną odpowiedź przy każdym podejściu, więc ponawianie
 * ich byłoby wyłącznie zwłoką przed wnioskiem znanym z pierwszej odpowiedzi. Dwa z nich
 * wymagają reakcji człowieka, dlatego trafiają do logu na poziomie ERROR: cisza w tym miejscu
 * oznaczałaby usługę, która przestała działać dla wszystkich, a wygląda jak brak ruchu.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.translation", name = "provider", havingValue = "deepl")
public class DeepLTranslationProvider implements TranslationProvider {

    /** Wyczerpany limit znaków konta - kod niestandardowy, specyficzny dla DeepL. */
    private static final int QUOTA_EXCEEDED = 456;

    private final RestClient restClient;

    public DeepLTranslationProvider(RestClient deepLRestClient, TranslationProperties properties) {
        String apiKey = properties.deepl().apiKey();

        /*
         * Kontrola klucza znajduje się tutaj, a nie w konfiguracji, ponieważ tylko tutaj wiadomo,
         * że ten dostawca został w ogóle wybrany. Wymuszanie klucza przy każdym uruchomieniu
         * zablokowałoby profil testowy i pracę lokalną na atrapie.
         *
         * Sprawdzenie obejmuje też literał nierozwiązanego symbolu zastępczego: przy wiązaniu
         * konfiguracji nieustawiona zmienna środowiskowa wpisuje do pola jego zapis tekstowy,
         * bez żadnego ostrzeżenia, więc sama kontrola pustej wartości by go nie wychwyciła.
         *
         * Komunikat nie zawiera wartości klucza - ten wyjątek trafia do konsoli i do kolektora
         * logów.
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
                    // Tablica tekstów, bo takiego kształtu oczekuje API; wysyłany jest jeden
                    // element. Dzielenie długich plików na części byłoby osobną decyzją i przy
                    // obowiązującym limicie rozmiaru pliku nie jest potrzebne.
                    .body(Map.of(
                            "text", List.of(text),
                            "target_lang", target.apiCode()))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, httpResponse) -> {
                        throw mapError(httpResponse.getStatusCode());
                    })
                    .body(DeepLResponse.class);

        } catch (ResourceAccessException e) {
            // Timeout połączenia lub odczytu i brak trasy do hosta są zawsze przejściowe.
            throw new TranslationProviderException("TRANSLATION_PROVIDER_UNAVAILABLE", true,
                    "Dostawca tłumaczenia nie odpowiedział", e);

        } catch (TranslationProviderException e) {
            throw e;

        } catch (RestClientException e) {
            // Odpowiedź, której nie dało się przetworzyć, traktowana jest jako przejściowa:
            // zdarza się, że zamiast danych wraca strona błędu wystawiona przez proxy.
            throw new TranslationProviderException("TRANSLATION_PROVIDER_UNAVAILABLE", true,
                    "Nieoczekiwana odpowiedź dostawcy tłumaczenia", e);
        }

        if (response == null || response.translations() == null || response.translations().isEmpty()) {
            // Odpowiedź poprawna, ale bez tłumaczenia. Nie ma czego ponawiać: to rozjazd kontraktu,
            // a kolejne identyczne żądanie da identyczny wynik.
            throw new TranslationProviderException("TRANSLATION_PROVIDER_REJECTED", false,
                    "Dostawca zwrócił odpowiedź bez tłumaczenia");
        }

        DeepLTranslation translation = response.translations().get(0);
        return new TranslationResult(translation.text(), translation.detectedSourceLanguage());
    }

    /* ---------------------------------------------------------------------------------
     * Ścieżka dokumentowa: wgranie dokumentu, sprawdzenie stanu, pobranie wyniku. Wszystkie
     * trzy wywołania używają metody POST, zgodnie z dokumentacją dostawcy - również sprawdzenie
     * stanu, mimo że niczego nie zmienia.
     * --------------------------------------------------------------------------------- */

    @Override
    public DocumentHandle uploadDocument(byte[] content, String filename, TargetLanguage target) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        // Nazwa pliku trafia do części multiparta, ponieważ to po niej dostawca rozpoznaje format.
        // Bez niej odrzuca żądanie, nie wiedząc, jaki dokument otrzymał.
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
                    // Odpowiedź 404 przy sprawdzaniu stanu oznacza, że dokumentu już nie ma -
                    // został pobrany albo wygasł. Nie jest to awaria, tylko sygnał do rozpoczęcia
                    // od nowa, dlatego ma własny typ wyjątku.
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
                     * Kod 503 przy pobieraniu ma u tego dostawcy dwa znaczenia naraz: tłumaczenie
                     * wciąż trwa albo dokument został już pobrany i skasowany. Z samej odpowiedzi
                     * nie da się ich rozróżnić, ale wołający schodzi tutaj dopiero po stanie
                     * zakończonym, więc pierwsze znaczenie odpada. Kod 404 oznacza to samo.
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
     * Wspólna obsługa awarii transportu dla wywołań dokumentowych, identyczna jak przy tłumaczeniu
     * tekstu. Wydzielona, ponieważ powtórzona przy każdym wywołaniu rozjechałaby się przy pierwszej
     * poprawce.
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

    /** Zamienia kod odpowiedzi dostawcy na wyjątek z informacją, czy zlecenie wolno ponowić. */
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
            // Bez treści odpowiedzi w komunikacie: przy błędzie uwierzytelnienia bywa w niej echo
            // nagłówka, a w nim klucz API.
            log.error("Dostawca tłumaczenia odrzucił uwierzytelnienie (HTTP {}) - sprawdź klucz API "
                    + "oraz to, czy adres pasuje do rodzaju konta (darmowe vs płatne)", code);
            return new TranslationProviderException("TRANSLATION_PROVIDER_REJECTED", false,
                    "Dostawca tłumaczenia odrzucił uwierzytelnienie");
        }
        return new TranslationProviderException("TRANSLATION_PROVIDER_REJECTED", false,
                "Dostawca tłumaczenia odrzucił żądanie (HTTP " + code + ")");
    }

    /**
     * Kształt odpowiedzi na tłumaczenie tekstu. Rekordy zamiast mapy sprawiają, że rozjazd
     * kontraktu ujawnia się przy deserializacji, a nie przy pierwszym odczycie zwracającym null.
     */
    record DeepLResponse(List<DeepLTranslation> translations) {
    }

    record DeepLTranslation(String detected_source_language, String text) {

        /** Nazwy pól odpowiadają JSON-owi dostawcy; ta metoda daje czytelną nazwę w kodzie. */
        String detectedSourceLanguage() {
            return detected_source_language;
        }
    }

    /** Odpowiedź na wgranie dokumentu - uchwyt potrzebny do dalszych wywołań. */
    record DocumentUpload(String document_id, String document_key) {
    }

    /**
     * Odpowiedź na sprawdzenie stanu dokumentu.
     *
     * Prognoza pozostałego czasu jest świadomie pomijana: wykonawca odpytuje w stałym rytmie
     * i nie ma jak jej wykorzystać poza wpisaniem do logu.
     */
    record DocumentStatusResponse(String status, Integer billed_characters, String error_message) {

        DocumentStatus toStatus() {
            DocumentStatus.State state = switch (status) {
                case "queued" -> DocumentStatus.State.QUEUED;
                case "translating" -> DocumentStatus.State.TRANSLATING;
                case "done" -> DocumentStatus.State.DONE;
                case "error" -> DocumentStatus.State.ERROR;
                // Nieznany stan traktowany jest jak błąd, a nie jak "wciąż trwa": odpytywanie
                // w nieskończoność o stan, którego aplikacja nie rozumie, zamieniłoby rozjazd
                // kontraktu w zlecenie wiszące na zawsze.
                default -> DocumentStatus.State.ERROR;
            };
            String message = state == DocumentStatus.State.ERROR && error_message == null
                    ? "Dostawca zwrócił nieznany status dokumentu: " + status
                    : error_message;
            return new DocumentStatus(state, billed_characters, message);
        }
    }

    /**
     * Zasób multipartowy z jawną nazwą pliku.
     *
     * Zwykły zasób bajtowy nazwy nie ma, więc część multiparta powstaje bez niej, a dostawca
     * rozpoznaje format właśnie po nazwie i takie żądanie odrzuca. Objaw jest mylący: błąd
     * wygląda na problem z zawartością dokumentu, a nie z nagłówkiem części.
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
