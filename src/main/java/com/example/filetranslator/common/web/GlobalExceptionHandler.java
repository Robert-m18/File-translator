/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.filetranslator.common.web;

import com.example.filetranslator.admin.exception.AdminActionRejectedException;
import com.example.filetranslator.admin.exception.AdminUserNotFoundException;
import com.example.filetranslator.common.exception.AccountBlockedException;
import com.example.filetranslator.common.exception.InvalidTokenException;
import com.example.filetranslator.common.exception.JwtAuthenticationException;
import com.example.filetranslator.common.exception.TokenExpiredException;
import com.example.filetranslator.translation.exception.InvalidUploadException;
import com.example.filetranslator.translation.exception.TranslationJobNotFoundException;
import com.example.filetranslator.translation.exception.TranslationNotReadyException;
import com.example.filetranslator.translation.exception.TranslationQuotaExceededException;
import com.example.filetranslator.translation.model.TargetLanguage;
import com.example.filetranslator.translation.storage.ObjectMissingException;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.beans.TypeMismatchException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.List;
import java.util.Map;

/**
 * Centralne mapowanie wyjątków na odpowiedzi HTTP w formacie RFC 9457 (ProblemDetail).
 *
 * Klasa dziedziczy po ResponseEntityExceptionHandler, dzięki czemu wyjątki rzucane przez sam
 * Spring MVC - nieparsowalny JSON, brakujący parametr, niedozwolona metoda, nieznana ścieżka -
 * również wracają jako ProblemDetail zamiast domyślnej strony błędu.
 *
 * Obowiązująca zasada: kontrolery i serwisy rzucają wyjątki dziedzinowe i nie znają kodów HTTP,
 * a całe mapowanie wyjątek - status znajduje się tutaj. Korzyścią jest jeden format odpowiedzi
 * dla wszystkich błędów i jedno miejsce, w którym widać, co API zwraca w każdej sytuacji.
 *
 * Nie ma tu handlera dla konfliktu adresu e-mail przy rejestracji, i jest to decyzja, a nie
 * przeoczenie: gotowa odpowiedź "adres już istnieje" stoi wprost naprzeciw zasady, że API nie
 * zdradza, które adresy są zarejestrowane.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    /** Nieprawidłowe poświadczenia logowania. */
    @ExceptionHandler(BadCredentialsException.class)
    public ProblemDetail handleBadCredentials(BadCredentialsException ex) {
        log.warn("Nieprawidłowe dane logowania");
        // Jeden komunikat dla nieznanego adresu i błędnego hasła - inaczej API pozwalałoby
        // sprawdzić, które adresy są zarejestrowane.
        return ApiProblem.of(HttpStatus.UNAUTHORIZED, "Błąd logowania",
                "Nieprawidłowy email lub hasło", "BAD_CREDENTIALS");
    }

    /**
     * Konto istnieje, ale jego adres nie został jeszcze potwierdzony (User.isEnabled() = false).
     */
    @ExceptionHandler(DisabledException.class)
    public ProblemDetail handleDisabled(DisabledException ex) {
        log.warn("Próba logowania na niepotwierdzone konto");
        return ApiProblem.of(HttpStatus.FORBIDDEN, "Konto niepotwierdzone",
                "Konto nie zostało jeszcze potwierdzone - sprawdź email", "ACCOUNT_NOT_CONFIRMED");
    }

    /**
     * Konto zablokowane po serii nieudanych logowań (User.isAccountNonLocked() = false).
     *
     * Odpowiedź nazywa przyczynę wprost i jest to świadomy kompromis: informacja o blokadzie
     * potwierdza napastnikowi istnienie adresu, ale prawowity użytkownik musi wiedzieć, dlaczego
     * poprawne hasło nie działa - bez tego zgłasza awarię zamiast poczekać na wygaśnięcie blokady.
     */
    @ExceptionHandler(LockedException.class)
    public ProblemDetail handleLocked(LockedException ex) {
        log.warn("Próba logowania na zablokowane konto");
        return ApiProblem.of(HttpStatus.LOCKED, "Konto zablokowane",
                "Konto zostało tymczasowo zablokowane po zbyt wielu nieudanych próbach logowania. "
                        + "Spróbuj ponownie później.", "ACCOUNT_LOCKED");
    }

    /**
     * Konto zablokowane przez administratora - stan odrębny od blokady po nieudanych logowaniach.
     *
     * Handler wygrywa z handlerem powyżej, ponieważ Spring wybiera metodę najbardziej szczegółową,
     * a ten wyjątek dziedziczy po LockedException. Dziedziczenie pełni tu rolę zabezpieczenia:
     * gdyby ten handler zniknął, odpowiedzią byłoby 423 ACCOUNT_LOCKED zamiast 500, więc
     * użytkownik dostałby gorszy komunikat, ale konto pozostałoby zamknięte.
     *
     * Osobny kod maszynowy jest konieczny, bo frontend się po nim rozgałęzia: blokada
     * administracyjna nie mija samoczynnie, więc rada "spróbuj później" byłaby nieprawdziwa.
     */
    @ExceptionHandler(AccountBlockedException.class)
    public ProblemDetail handleAccountBlocked(AccountBlockedException ex) {
        log.warn("Próba użycia konta zablokowanego przez administratora");
        return ApiProblem.of(HttpStatus.LOCKED, "Konto zablokowane",
                "Konto zostało zablokowane przez administratora. Skontaktuj się z obsługą.",
                "ACCOUNT_BLOCKED");
    }

    /** Panel administracyjny: konto o podanym identyfikatorze nie istnieje. */
    @ExceptionHandler(AdminUserNotFoundException.class)
    public ProblemDetail handleAdminUserNotFound(AdminUserNotFoundException ex) {
        log.warn("Panel administracyjny: nie znaleziono konta o podanym id");
        return ApiProblem.of(HttpStatus.NOT_FOUND, "Nie znaleziono",
                ex.getMessage(), "USER_NOT_FOUND");
    }

    /**
     * Akcja panelu odrzucona ze względu na stan systemu (operacja na własnym koncie, ostatni
     * administrator).
     *
     * Status 409, ponieważ samo żądanie jest poprawne - niewykonalnym czyni je stan po stronie
     * serwera. Kod maszynowy pochodzi z wyjątku, bo przyczyn jest kilka, a frontend komunikuje
     * każdą inaczej.
     */
    @ExceptionHandler(AdminActionRejectedException.class)
    public ProblemDetail handleAdminActionRejected(AdminActionRejectedException ex) {
        log.warn("Panel administracyjny: odrzucono akcję ({})", ex.getCode());
        return ApiProblem.of(HttpStatus.CONFLICT, "Akcja niedozwolona",
                ex.getMessage(), ex.getCode());
    }

    /** Token z linku (potwierdzenie adresu, reset hasła) jest nieprawidłowy. */
    @ExceptionHandler(InvalidTokenException.class)
    public ProblemDetail handleInvalidToken(InvalidTokenException ex) {
        log.warn("Nieprawidłowy token: {}", ex.getMessage());
        return ApiProblem.of(HttpStatus.BAD_REQUEST, "Nieprawidłowy token", ex.getMessage(), "INVALID_TOKEN");
    }

    /** Token z linku był poprawny, ale minął jego termin ważności. */
    @ExceptionHandler(TokenExpiredException.class)
    public ProblemDetail handleTokenExpired(TokenExpiredException ex) {
        log.warn("Token wygasł: {}", ex.getMessage());
        return ApiProblem.of(HttpStatus.GONE, "Token wygasł", ex.getMessage(), "TOKEN_EXPIRED");
    }

    /**
     * Błąd tokenu JWT. Kod maszynowy z wyjątku trafia wprost do odpowiedzi, dzięki czemu
     * frontend odróżnia sytuację wymagającą odświeżenia sesji od wymagającej wylogowania.
     */
    @ExceptionHandler(JwtAuthenticationException.class)
    public ProblemDetail handleJwtAuthentication(JwtAuthenticationException ex) {
        log.warn("Błąd JWT: {} - {}", ex.getTokenError(), ex.getMessage());
        return ApiProblem.of(HttpStatus.UNAUTHORIZED, "Błąd uwierzytelnienia",
                ex.getMessage(), ex.getTokenError());
    }

    /**
     * Odmowa dostępu zgłoszona przez zabezpieczenia na poziomie metod.
     *
     * Odmowy pochodzące z filtrów Spring Security tutaj nie docierają - obsługuje je
     * RestAccessDeniedHandler. Bez tej metody wyjątek wpadłby w handler ogólny i wrócił jako
     * 500 zamiast 403.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail handleAccessDenied(AccessDeniedException ex) {
        log.warn("Odmowa dostępu: {}", ex.getMessage());
        return ApiProblem.of(HttpStatus.FORBIDDEN, "Brak uprawnień",
                "Nie masz uprawnień do tego zasobu", "ACCESS_DENIED");
    }

    /**
     * Wgrany plik nie nadaje się do tłumaczenia (pusty, nieobsługiwany format, złe kodowanie).
     * Kod maszynowy pochodzi z wyjątku, bo przyczyn jest kilka, a odpowiedź jedna.
     */
    @ExceptionHandler(InvalidUploadException.class)
    public ProblemDetail handleInvalidUpload(InvalidUploadException ex) {
        log.warn("Odrzucono przesłany plik: {}", ex.getCode());
        return ApiProblem.of(HttpStatus.BAD_REQUEST, "Nieprawidłowy plik",
                ex.getMessage(), ex.getCode());
    }

    /** Zlecenie nie istnieje albo należy do kogoś innego - z zewnątrz nierozróżnialne. */
    @ExceptionHandler(TranslationJobNotFoundException.class)
    public ProblemDetail handleTranslationJobNotFound(TranslationJobNotFoundException ex) {
        // Identyfikator nie trafia do logu: ta sama odpowiedź należy się zleceniu nieistniejącemu
        // i cudzemu, a rozróżnianie ich w logu zachęca do rozróżnienia ich w odpowiedzi.
        log.warn("Nie znaleziono zlecenia tłumaczenia dla bieżącego użytkownika");
        return ApiProblem.of(HttpStatus.NOT_FOUND, "Nie znaleziono",
                ex.getMessage(), "TRANSLATION_JOB_NOT_FOUND");
    }

    /**
     * Zlecenie istnieje, ale nie ma jeszcze wyniku. Status trafia do ciała odpowiedzi, ponieważ
     * tylko on mówi frontendowi, czy odpytywać dalej, czy przestać.
     */
    @ExceptionHandler(TranslationNotReadyException.class)
    public ProblemDetail handleTranslationNotReady(TranslationNotReadyException ex) {
        ProblemDetail problem = ApiProblem.of(HttpStatus.CONFLICT, "Tłumaczenie niegotowe",
                ex.getMessage(), "TRANSLATION_NOT_READY");
        problem.setProperty("status", ex.getStatus());
        return problem;
    }

    /**
     * Wiersz zlecenia istnieje, ale jego pliku nie ma już w magazynie obiektowym.
     *
     * Status 410, a nie 404 ani 500: odpowiedź 404 byłaby nieprawdą, bo zlecenie istnieje i widać
     * je na liście, a 500 sugerowałoby awarię do zgłoszenia, podczas gdy jest to stan przewidziany.
     *
     * Sytuacja wynika z tego, że retencja działa w dwóch miejscach - wiersze kasuje zadanie
     * aplikacji, pliki reguła wygasania na kubełku - a obie wartości wiąże wyłącznie uważność.
     * Osobny kod odpowiedzi daje rozjazdowi tych wartości rozpoznawalny objaw: powtarzające się
     * odpowiedzi tego rodzaju dla zleceń młodszych niż retencja oznaczają, że reguła na kubełku
     * kasuje pliki za wcześnie.
     */
    @ExceptionHandler(ObjectMissingException.class)
    public ProblemDetail handleObjectMissing(ObjectMissingException ex) {
        log.warn("Plik zlecenia nie istnieje już w magazynie - sprawdź regułę wygasania "
                + "na kubełku wobec app.translation.retention");
        return ApiProblem.of(HttpStatus.GONE, "Plik niedostępny",
                "Plik nie jest już przechowywany - zleć tłumaczenie ponownie", "CONTENT_EXPIRED");
    }

    /** Wyczerpany dobowy limit znaków. Pozostały budżet wraca w ciele, żeby dało się go pokazać. */
    @ExceptionHandler(TranslationQuotaExceededException.class)
    public ProblemDetail handleTranslationQuota(TranslationQuotaExceededException ex) {
        ProblemDetail problem = ApiProblem.of(HttpStatus.TOO_MANY_REQUESTS, "Limit wyczerpany",
                ex.getMessage(), "TRANSLATION_QUOTA_EXCEEDED");
        problem.setProperty("remainingChars", ex.getRemainingChars());
        return problem;
    }

    /**
     * Siatka bezpieczeństwa na naruszenia więzów bazy - na przykład wyścig przy równoczesnej
     * rejestracji tego samego adresu, którego nie wychwyci wcześniejsze sprawdzenie.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleDataIntegrity(DataIntegrityViolationException ex) {
        log.warn("Naruszenie więzów integralności: {}", constraintOf(ex));
        return ApiProblem.of(HttpStatus.CONFLICT, "Konflikt danych",
                "Operacja narusza ograniczenia bazy danych", "DATA_INTEGRITY_VIOLATION");
    }

    /**
     * Wydobywa nazwę naruszonego więzu - świadomie zamiast komunikatu sterownika.
     *
     * Komunikat sterownika zawiera wartość, która wywołała kolizję, i dotyczy to każdego silnika
     * bazy. Najczęstszym wyzwalaczem tego handlera jest wyścig przy rejestracji na ten sam adres,
     * więc logowanie komunikatu wprost wpisywałoby adresy użytkowników do logu produkcyjnego.
     * Nazwa więzu niesie tę samą wartość diagnostyczną i nie zawiera danych osobowych.
     */
    private String constraintOf(DataIntegrityViolationException ex) {
        // Przejście po całym łańcuchu przyczyn zamiast getMostSpecificCause(): tamta metoda
        // schodzi do najgłębszego wyjątku, czyli do wyjątku sterownika, a wyjątek Hibernate'a
        // niosący nazwę więzu znajduje się piętro wyżej.
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation
                    && violation.getConstraintName() != null) {
                return violation.getConstraintName();
            }
        }
        // Nierozpoznany więz - zostaje sam typ przyczyny. Świadomie mniej informacji niż
        // w komunikacie, bo przy nieznanym błędzie nie wiadomo, co ten komunikat zawiera.
        return ex.getMostSpecificCause().getClass().getSimpleName();
    }

    /**
     * Błędy walidacji ciała żądania.
     *
     * Odpowiedź niesie listę błędów w rozbiciu na pola, a nie sklejony tekst, dzięki czemu
     * frontend podświetla konkretne pola formularza zamiast wyświetlać jeden komunikat zbiorczy.
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> Map.of(
                        "field", error.getField(),
                        "message", error.getDefaultMessage() == null ? "Nieprawidłowa wartość" : error.getDefaultMessage()
                ))
                .toList();

        log.warn("Błąd walidacji: {}", errors);

        ProblemDetail problem = ApiProblem.of(HttpStatus.BAD_REQUEST, "Błąd walidacji",
                "Żądanie zawiera nieprawidłowe dane", "VALIDATION_FAILED");
        problem.setProperty("errors", errors);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problem);
    }

    /**
     * Przekroczony rozmiar wgrywanego pliku. Wyjątek powstaje przy parsowaniu multiparta, czyli
     * przed wejściem do kontrolera - dlatego tego przypadku nie da się obsłużyć w walidacji uploadu.
     *
     * Metoda bazowa jest nadpisana zamiast dodania własnego handlera, ponieważ
     * ResponseEntityExceptionHandler sam deklaruje obsługę tego wyjątku, a drugie mapowanie na ten
     * sam typ przerywa start aplikacji komunikatem o niejednoznacznym handlerze. Ta sama zasada
     * obowiązuje przy każdym wyjątku, który Spring MVC już zna.
     *
     * Status 413 zamiast 400 informuje klienta, że problemem jest rozmiar, a nie treść.
     */
    @Override
    protected ResponseEntity<Object> handleMaxUploadSizeExceededException(MaxUploadSizeExceededException ex,
                                                                          HttpHeaders headers,
                                                                          HttpStatusCode status,
                                                                          WebRequest request) {
        log.warn("Odrzucono przesłany plik - przekroczony rozmiar");
        ProblemDetail problem = ApiProblem.of(HttpStatus.PAYLOAD_TOO_LARGE, "Plik za duży",
                "Przesłany plik przekracza dopuszczalny rozmiar", "FILE_TOO_LARGE");
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(problem);
    }

    /**
     * Parametr żądania nie dał się przekonwertować na oczekiwany typ.
     *
     * Nadpisanie służy przede wszystkim językowi docelowemu: domyślna odpowiedź informuje
     * o nieprawidłowej wartości, ale nie podaje, co wolno podać, więc użytkownik musi zgadywać.
     * Przy typie wyliczeniowym lista dopuszczalnych wartości jest znana z definicji i trafia
     * do odpowiedzi.
     */
    @Override
    protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException ex,
                                                        HttpHeaders headers,
                                                        HttpStatusCode status,
                                                        WebRequest request) {
        if (TargetLanguage.class.equals(ex.getRequiredType())) {
            log.warn("Nieobsługiwany język docelowy w żądaniu");
            ProblemDetail problem = ApiProblem.of(HttpStatus.BAD_REQUEST, "Nieobsługiwany język",
                    "Nieobsługiwany język docelowy. Dozwolone wartości: " + TargetLanguage.allowedValues(),
                    "UNSUPPORTED_TARGET_LANGUAGE");
            problem.setProperty("allowed", TargetLanguage.values());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problem);
        }
        return super.handleTypeMismatch(ex, headers, status, request);
    }

    /**
     * Handler ogólny - ostatnia linia obrony.
     *
     * Szczegóły trafiają do logów, a do klienta idzie komunikat ogólny: ślad stosu czy komunikat
     * bazy w odpowiedzi HTTP byłyby wyciekiem informacji o systemie. Powiązanie odpowiedzi
     * z wpisem w logu zapewnia identyfikator żądania dokładany przez ApiProblem.
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleGeneric(Exception ex) {
        log.error("Nieoczekiwany błąd", ex);
        return ApiProblem.of(HttpStatus.INTERNAL_SERVER_ERROR, "Błąd serwera",
                "Wewnętrzny błąd serwera", "INTERNAL_ERROR");
    }
}
