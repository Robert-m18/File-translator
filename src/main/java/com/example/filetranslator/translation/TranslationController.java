/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.filetranslator.translation;

import com.example.filetranslator.translation.dto.TranslationContent;
import com.example.filetranslator.translation.dto.TranslationJobResponse;
import com.example.filetranslator.translation.model.TargetLanguage;
import com.example.filetranslator.user.model.User;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * API zleceń tłumaczenia plików: wgranie, podgląd stanu, pobranie wyniku i usunięcie.
 *
 * Wszystkie ścieżki wymagają zalogowania, a operacje zmieniające stan dodatkowo nagłówka CSRF.
 * Tożsamość niesie ciasteczko, więc frontend musi wołać te endpointy z dołączonymi ciasteczkami.
 *
 * Kontroler jest wyłącznie warstwą HTTP: rozpakowuje żądanie, woła serwis i buduje odpowiedź.
 * Mapowanie wyjątków na kody stanu znajduje się w centralnym handlerze.
 */
@RestController
@RequestMapping("/translations")
@RequiredArgsConstructor
public class TranslationController {

    /**
     * Sufit rozmiaru strony. Bez niego jeden parametr w adresie zamieniłby listę w zapytanie
     * zwracające wszystko, co użytkownik kiedykolwiek zlecił.
     */
    private static final int MAX_PAGE_SIZE = 100;

    private final TranslationService translationService;

    /**
     * Przyjmuje plik i zwraca utworzone zlecenie ze statusem oczekującym.
     *
     * Odpowiedź ma kod 202, a nie 201, ponieważ w chwili odpowiedzi nic jeszcze nie jest
     * przetłumaczone - powstało wyłącznie zlecenie, a wynik pojawi się po przetworzeniu przez
     * wykonawcę kolejki. Klient otrzymuje identyfikator i odpytuje o stan, aż przestanie on być
     * stanem oczekiwania.
     */
    @PostMapping
    public ResponseEntity<TranslationJobResponse> submit(
            @RequestPart("file") MultipartFile file,
            @RequestParam("targetLang") TargetLanguage targetLang,
            @AuthenticationPrincipal User user) {

        UploadedFile uploaded = UploadedFile.from(file);
        TranslationJobResponse response = translationService.submit(user, uploaded, targetLang);

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    @GetMapping
    public Page<TranslationJobResponse> list(@AuthenticationPrincipal User user,
                                             Pageable pageable) {
        return translationService.listOwn(user, capped(pageable));
    }

    @GetMapping("/{id}")
    public TranslationJobResponse get(@PathVariable Long id,
                                      @AuthenticationPrincipal User user) {
        return translationService.getOwn(user, id);
    }

    /**
     * Zwraca wynik tłumaczenia jako plik do pobrania.
     *
     * Plik leci strumieniem z magazynu, a nie przez tablicę bajtów: wczytanie całości na stertę
     * tylko po to, żeby zaraz zapisać ją do gniazda, mnożyłoby zużycie pamięci przez liczbę
     * równoczesnych pobrań. Strumień zamyka Spring po zapisaniu ciała odpowiedzi.
     *
     * Pobieranie idzie przez aplikację, a nie przez przekierowanie na adres podpisany po stronie
     * magazynu, mimo że tamto rozwiązanie odciążyłoby serwer. Dostępu do cudzego zlecenia pilnuje
     * warunek na identyfikator właściciela w zapytaniu, więc cudzy identyfikator daje odpowiedź
     * "nie znaleziono". Adres podpisany jest linkiem na okaziciela - kto go przechwyci, pobierze
     * plik bez ciasteczka, a samo jego wydanie potwierdza, że zlecenie o danym identyfikatorze
     * istnieje. Do rewizji tej decyzji skłoniłyby dopiero pliki na tyle duże, że przesyłanie ich
     * przez aplikację zacznie kosztować.
     */
    @GetMapping("/{id}/content")
    public ResponseEntity<Resource> download(@PathVariable Long id,
                                             @AuthenticationPrincipal User user) {

        TranslationContent content = translationService.openOwnResult(user, id);

        /*
         * Nagłówek budowany przez dedykowany builder, nigdy przez sklejanie tekstu. Nazwa pochodzi
         * od użytkownika, więc cudzysłów albo znak nowej linii w niej byłyby wstrzyknięciem
         * nagłówka, a znaki spoza ASCII wymagają osobnego kodowania. Builder obsługuje jedno
         * i drugie.
         */
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(translatedFilename(content), StandardCharsets.UTF_8)
                .build();

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                // Typ treści z rozpoznanego formatu, a nie stały: przeglądarka po nim decyduje,
                // co zrobić z plikiem, a dokument podany jako tekst otwiera się w karcie jako
                // ciąg nieczytelnych znaków zamiast trafić do właściwego programu.
                .contentType(MediaType.parseMediaType(content.fileType().contentType()))
                // Długość znana z metadanych obiektu - bez niej odpowiedź leci kodowaniem
                // porcjowym i przeglądarka nie pokazuje postępu pobierania.
                .contentLength(content.object().size())
                .body(new InputStreamResource(content.object().content()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id,
                                       @AuthenticationPrincipal User user) {
        translationService.deleteOwn(user, id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Buduje nazwę pliku wynikowego przez dopisanie kodu języka przed rozszerzeniem.
     *
     * Rozszerzenie pochodzi z rozpoznanego formatu, a nie z nazwy przysłanej przez klienta,
     * i zostaje na końcu nazwy, gdzie rozpoznaje je system operacyjny użytkownika.
     */
    private String translatedFilename(TranslationContent content) {
        String extension = content.fileType().extension();
        String original = content.originalFilename();
        int dot = original.toLowerCase(Locale.ROOT).lastIndexOf(extension);
        String base = dot > 0 ? original.substring(0, dot) : original;
        return base + "-" + content.targetLang().apiCode() + extension;
    }

    private Pageable capped(Pageable pageable) {
        if (pageable.getPageSize() <= MAX_PAGE_SIZE) {
            return pageable;
        }
        return PageRequest.of(pageable.getPageNumber(), MAX_PAGE_SIZE, pageable.getSort());
    }
}
