/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.filetranslator.translation.provider;

import com.example.filetranslator.translation.model.TargetLanguage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dostawca, który niczego nie tłumaczy - oznacza tylko każdą linię kodem języka docelowego.
 *
 * DLACZEGO ISTNIEJE, choć nie tłumaczy: bez niego całego przepływu nie da się uruchomić
 * bez konta u zewnętrznego dostawcy. Konkretnie kupuje trzy rzeczy:
 *  - przebieg testów zostaje HERMETYCZNY (żadnego ruchu na zewnątrz, żadnego sekretu
 *    w repozytorium, żadnej zależności od tego, czy cudzy serwis akurat działa). Testy
 *    podnoszą sobie bazę, Redisa i magazyn w kontenerach, ale dostawcy tłumaczeń nie
 *    postawi żaden kontener - to usługa zewnętrzna, która kosztuje znaki,
 *  - `docker compose up` daje działającą aplikację od pierwszego uruchomienia, tak samo
 *    jak Mailpit daje działającą pocztę bez konta u dostawcy poczty,
 *  - testy workera sprawdzają kolejkę, rezerwację i ponowienia bez zgadywania, co odpowie
 *    obce API.
 *
 * Znacznik jest CELOWO widoczny w treści: gdyby ta implementacja przypadkiem wjechała na
 * produkcję, użytkownik zobaczy to w pierwszym pobranym pliku, a nie odkryje po miesiącu,
 * że "tłumaczenia jakoś słabo wychodzą".
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.translation", name = "provider",
        havingValue = "echo", matchIfMissing = true)
public class EchoTranslationProvider implements TranslationProvider {

    /** Nie ma jak wykryć języka, więc mówimy to wprost zamiast zgadywać. */
    private static final String UNKNOWN_SOURCE = "AUTO";

    public EchoTranslationProvider() {
        // WARN, nie INFO: na produkcji to jest stan wymagający reakcji, a nie informacja.
        log.warn("Aktywny atrapowy dostawca tłumaczenia (echo) - teksty NIE są tłumaczone. "
                + "Do prawdziwego tłumaczenia ustaw app.translation.provider=deepl");
    }

    @Override
    public TranslationResult translate(String text, TargetLanguage target) {
        String marked = text.lines()
                .map(line -> "[[" + target.apiCode() + "]] " + line)
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");

        return new TranslationResult(marked, UNKNOWN_SOURCE);
    }

    /* ---------------------------------------------------------------------------------
     * Dokumenty. Atrapa ODDAJE PLIK BEZ ZMIAN - i to jest tu jedyna uczciwa odpowiedź:
     * doklejenie znacznika do PDF-a albo XLSX-a dałoby plik uszkodzony, czyli objaw
     * wyglądający na błąd w kodzie zamiast na atrapę. Ostrzeżenie przy starcie mówi wprost,
     * że nic nie jest tłumaczone.
     *
     * Odwzorowuje za to DWIE własności protokołu, na których stoi worker, bo inaczej testy
     * na atrapie nie sprawdzałyby tego, co dzieje się naprawdę:
     *  - dokument da się pobrać TYLKO RAZ (potem DocumentUnavailableException),
     *  - uchwyt jest nieprzezroczysty i trzeba go zapamiętać, żeby wrócić po wynik.
     * --------------------------------------------------------------------------------- */

    private final Map<String, byte[]> documents = new ConcurrentHashMap<>();

    @Override
    public DocumentHandle uploadDocument(byte[] content, String filename, TargetLanguage target) {
        String documentId = UUID.randomUUID().toString();
        documents.put(documentId, content.clone());
        return new DocumentHandle(documentId, UUID.randomUUID().toString());
    }

    @Override
    public DocumentStatus checkDocument(DocumentHandle handle) {
        byte[] content = documents.get(handle.documentId());
        if (content == null) {
            // Pobrany albo nieznany - dla workera to ten sam przypadek: trzeba zacząć od nowa.
            return new DocumentStatus(DocumentStatus.State.ERROR, null, "Dokument nie istnieje");
        }
        // Atrapa nie ma czego kolejkować, więc jest gotowa od razu. Ścieżkę odpytywania
        // (QUEUED/TRANSLATING) sprawdza test podstawiający własną implementację portu.
        return new DocumentStatus(DocumentStatus.State.DONE, content.length, null);
    }

    @Override
    public byte[] downloadDocument(DocumentHandle handle) {
        byte[] content = documents.remove(handle.documentId());
        if (content == null) {
            throw new DocumentUnavailableException("Dokument został już pobrany albo nie istnieje");
        }
        return content;
    }
}
