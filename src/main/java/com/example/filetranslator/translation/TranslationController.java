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
 * Zlecenia tłumaczenia plików.
 *
 * Wszystkie ścieżki wymagają zalogowania (reguła w SecurityConfig), a POST i DELETE dodatkowo
 * nagłówka CSRF - tak jak każda inna operacja zmieniająca stan w tym API. Tożsamość niesie
 * ciasteczko accessToken, więc frontend musi wołać te endpointy z credentials: 'include'.
 *
 * Kontroler jest wyłącznie warstwą HTTP: rozpakowuje żądanie, woła serwis, buduje odpowiedź.
 * Mapowanie wyjątków na kody stanu siedzi w GlobalExceptionHandler.
 */
@RestController
@RequestMapping("/translations")
@RequiredArgsConstructor
public class TranslationController {

    /**
     * Sufit rozmiaru strony. Bez niego ?size=100000 zamienia listę w zapytanie zwracające
     * wszystko, co użytkownik kiedykolwiek zlecił - jednym parametrem w adresie.
     */
    private static final int MAX_PAGE_SIZE = 100;

    private final TranslationService translationService;

    /**
     * 202, nie 201: w tej chwili nic jeszcze nie jest przetłumaczone. Powstało zlecenie,
     * a wynik pojawi się dopiero po przetworzeniu przez workera - tak samo jak rejestracja
     * odpowiada 202, bo konto powstaje dopiero po potwierdzeniu adresu.
     *
     * Klient dostaje id i odpytuje GET /translations/{id}, aż status przestanie być
     * PENDING/PROCESSING.
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
     * Wynik jako plik do pobrania.
     *
     * text/plain z jawnym UTF-8 i Content-Disposition: attachment, więc przeglądarka
     * zapisuje plik zamiast wyświetlać treść, a polskie znaki nie zamieniają się w krzaki.
     *
     * PLIK LECI STRUMIENIEM Z MAGAZYNU, nie przez tablicę bajtów: przy większych plikach
     * wczytanie całości na stertę tylko po to, żeby ją zaraz zapisać do gniazda, mnoży
     * zużycie pamięci przez liczbę równoczesnych pobrań. Strumień zamyka Spring po zapisaniu
     * ciała odpowiedzi.
     *
     * DLACZEGO NIE PRZEKIEROWANIE NA PRESIGNED URL, mimo że odciążyłoby aplikację: dostępu
     * do cudzego zlecenia pilnuje warunek na user_id w zapytaniu, dzięki czemu cudze id daje
     * 404, a nie 403. Presigned URL to link OKAZICIELSKI - kto go przechwyci, pobierze plik
     * bez ciasteczka, a samo jego wydanie potwierdza, że zlecenie o tym id istnieje.
     * Wyzwalacz do rewizji: pliki na tyle duże, że przesyłanie ich przez aplikację zacznie
     * kosztować - wtedy cena jest płacona świadomie.
     */
    @GetMapping("/{id}/content")
    public ResponseEntity<Resource> download(@PathVariable Long id,
                                             @AuthenticationPrincipal User user) {

        TranslationContent content = translationService.openOwnResult(user, id);

        /*
         * Nagłówek budowany przez ContentDisposition, NIGDY przez sklejanie tekstu.
         * Nazwa pochodzi od użytkownika: cudzysłów albo znak nowej linii w niej to
         * wstrzyknięcie nagłówka, a polskie znaki wymagają kodowania RFC 5987 (filename*).
         * Builder robi jedno i drugie sam.
         */
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(translatedFilename(content), StandardCharsets.UTF_8)
                .build();

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                // Typ treści z ROZPOZNANEGO formatu, nie na sztywno text/plain: przeglądarka
                // po nim decyduje, co zrobić z plikiem, a PDF podany jako tekst otwiera się
                // w karcie jako krzaki zamiast trafić do czytnika.
                .contentType(MediaType.parseMediaType(content.fileType().contentType()))
                // Content-Length znany z metadanych obiektu - bez niego odpowiedź leci
                // kodowaniem porcjowym i przeglądarka nie pokazuje postępu pobierania.
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
     * "lista.txt" + EN-GB -> "lista-EN-GB.txt". Rozszerzenie zostaje na końcu, gdzie ma być,
     * i bierze się z ROZPOZNANEGO formatu - nie z nazwy przysłanej przez klienta.
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
