/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.filetranslator.translation.provider;

import com.example.filetranslator.translation.TranslationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Klient HTTP do DeepL.
 *
 * Wydzielone z DeepLTranslationProvider, żeby test tamtej klasy mógł podstawić własny
 * RestClient.Builder (MockRestServiceServer) i sprawdzić mapowanie błędów bez sieci.
 *
 * DWIE RZECZY, KTÓRE MUSZĄ TU BYĆ USTAWIONE JAWNIE:
 *
 * 1. TIMEOUTY. Domyślnie RestClient nie ma limitu odczytu - zawieszony serwer dostawcy
 *    trzymałby wątek z puli tłumaczeń dopóty, dopóki nie zamknie połączenia druga strona.
 *    Przy app.translation.concurrency=2 wystarczą DWA takie żądania, żeby kolejka stanęła
 *    całkowicie, i nie pojawi się przy tym ani jeden wpis w logu ani żaden błąd - zlecenia
 *    będą po prostu wisieć w PROCESSING. To jest najgorszy możliwy tryb awarii: cichy.
 *
 * 2. NAGŁÓWEK AUTORYZACJI ustawiany raz, dla całego klienta. Dopisywany przy każdym
 *    wywołaniu prędzej czy później zostanie gdzieś pominięty, a objawem będzie 403,
 *    czyli komunikat wskazujący na zły klucz, a nie na brakujący nagłówek.
 */
@Configuration
@ConditionalOnProperty(prefix = "app.translation", name = "provider", havingValue = "deepl")
public class DeepLClientConfig {

    @Bean
    public RestClient deepLRestClient(RestClient.Builder builder, TranslationProperties properties) {
        TranslationProperties.DeepL deepl = properties.deepl();

        ClientHttpRequestFactory requestFactory = ClientHttpRequestFactoryBuilder.detect()
                .build(HttpClientSettings.defaults()
                        .withConnectTimeout(deepl.connectTimeout())
                        .withReadTimeout(deepl.readTimeout()));

        return builder
                .baseUrl(deepl.apiUrl())
                .requestFactory(requestFactory)
                // Format wymagany przez DeepL - nie Bearer.
                .defaultHeader(HttpHeaders.AUTHORIZATION, "DeepL-Auth-Key " + deepl.apiKey())
                .build();
    }
}
