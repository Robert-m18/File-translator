package com.example.robert;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Nagłówki, które frontend na innym origin MUSI móc odczytać.
 *
 * DLACZEGO TO OSOBNY TEST: przeglądarka udostępnia JavaScriptowi wyłącznie nagłówki
 * wymienione w Access-Control-Expose-Headers - reszta jest dla niego niewidoczna, mimo że
 * fizycznie przyszła w odpowiedzi. Objaw pominięcia jest CICHY, bo żądanie kończy się
 * sukcesem: pobieranie pliku działa, tylko nazwa zaproponowana przez serwer nie dociera
 * i front zapisuje plik pod nazwą awaryjną.
 *
 * Znalezione 2026-08-10 przez przejście przepływu w przeglądarce - plik "lista-FR.txt"
 * zapisał się jako "tlumaczenie.txt". To ta sama klasa błędu co odpowiedź 429 bez nagłówków
 * CORS: kod po stronie serwera jest poprawny od zawsze, po prostu nigdy nie dociera tam,
 * gdzie miał.
 *
 * Zwykłe testy MockMvc tego nie wyłapią, bo pytają serwer bezpośrednio, bez polityki CORS
 * i bez przeglądarki, która cokolwiek by ukryła.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CorsExposedHeadersTest {

    /** Musi zgadzać się z app.frontend.url na profilu testowym - inaczej CORS odrzuci origin. */
    private static final String FRONTEND_ORIGIN = "http://localhost:5173";

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("Nazwa pobieranego pliku jest odczytywalna przez frontend na innym origin")
    void contentDisposition_shouldBeExposedToBrowser() throws Exception {
        mockMvc.perform(get("/actuator/health").header(HttpHeaders.ORIGIN, FRONTEND_ORIGIN))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS,
                        containsString(HttpHeaders.CONTENT_DISPOSITION)));
    }

    /**
     * traceId jest jedynym uchwytem, po którym da się odnaleźć konkretne żądanie w logach.
     * Bez wystawienia nagłówka użytkownik zgłaszający "wyskoczył błąd" nie ma czego podać.
     */
    @Test
    @DisplayName("Nagłówek korelacji jest odczytywalny przez frontend na innym origin")
    void traceId_shouldBeExposedToBrowser() throws Exception {
        mockMvc.perform(get("/actuator/health").header(HttpHeaders.ORIGIN, FRONTEND_ORIGIN))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS,
                        containsString("X-Request-Id")));
    }
}
