/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.robert.common.web;

import com.example.robert.admin.exception.AdminActionRejectedException;
import com.example.robert.admin.exception.AdminUserNotFoundException;
import com.example.robert.common.exception.AccountBlockedException;
import com.example.robert.common.exception.InvalidTokenException;
import com.example.robert.common.exception.JwtAuthenticationException;
import com.example.robert.common.exception.TokenExpiredException;
import com.example.robert.translation.exception.InvalidUploadException;
import com.example.robert.translation.exception.TranslationJobNotFoundException;
import com.example.robert.translation.exception.TranslationNotReadyException;
import com.example.robert.translation.exception.TranslationQuotaExceededException;
import com.example.robert.translation.model.TargetLanguage;
import com.example.robert.translation.storage.ObjectMissingException;
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
 * Centralny handler wyjątków - jedno miejsce, w którym wyjątek zamienia się w odpowiedź HTTP.
 *
 * Dziedziczy po ResponseEntityExceptionHandler, dzięki czemu wyjątki rzucane przez sam
 * Spring MVC (nieparsowalny JSON, brakujący parametr, zła metoda HTTP, 404 na nieznanej
 * ścieżce) też wracają jako ProblemDetail, a nie jako domyślna strona błędu Spring Boota.
 *
 * Zasada: kontrolery i serwisy rzucają wyjątki dziedzinowe i nie wiedzą nic o kodach HTTP.
 * Mapowanie wyjątek -> status siedzi wyłącznie tutaj.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    /*
     * Nie ma tu handlerów NotFoundException i EmailAlreadyExistException - oba wyjątki
     * zniknęły razem z martwym CRUD-em w UserService. Drugi z nich był wręcz pułapką:
     * odpowiedź 409 EMAIL_ALREADY_EXISTS stoi wprost naprzeciw zasady, że API nie zdradza,
     * które adresy są zarejestrowane (patrz handleBadCredentials niżej i AuthService.register).
     * Gotowy handler kusiłby, żeby go użyć przy pierwszym lepszym konflikcie adresu.
     *
     * Nieznana ścieżka HTTP i tak wraca jako ProblemDetail - obsługuje ją
     * ResponseEntityExceptionHandler, po którym ta klasa dziedziczy.
     */

    @ExceptionHandler(BadCredentialsException.class)
    public ProblemDetail handleBadCredentials(BadCredentialsException ex) {
        log.warn("Nieprawidłowe dane logowania");
        // Celowo jeden komunikat dla złego emaila i złego hasła - inaczej API
        // pozwalałoby sprawdzić, które adresy są zarejestrowane (enumeracja użytkowników).
        return ApiProblem.of(HttpStatus.UNAUTHORIZED, "Błąd logowania",
                "Nieprawidłowy email lub hasło", "BAD_CREDENTIALS");
    }

    /**
     * Rzucane przez Spring Security, gdy User.isEnabled() zwraca false -
     * konto istnieje, ale email nie został jeszcze potwierdzony.
     */
    @ExceptionHandler(DisabledException.class)
    public ProblemDetail handleDisabled(DisabledException ex) {
        log.warn("Próba logowania na niepotwierdzone konto");
        return ApiProblem.of(HttpStatus.FORBIDDEN, "Konto niepotwierdzone",
                "Konto nie zostało jeszcze potwierdzone - sprawdź email", "ACCOUNT_NOT_CONFIRMED");
    }

    /**
     * Rzucane przez Spring Security, gdy User.isAccountNonLocked() zwraca false -
     * konto zablokowane po serii nieudanych logowań.
     *
     * Zwracamy 423 Locked i wprost mówimy, co się stało. To świadomy kompromis:
     * informacja "konto zablokowane" potwierdza napastnikowi, że dany adres istnieje,
     * ale prawowity użytkownik musi wiedzieć, dlaczego poprawne hasło nie działa -
     * bez tego zgłasza awarię, a nie czeka na wygaśnięcie blokady.
     */
    @ExceptionHandler(LockedException.class)
    public ProblemDetail handleLocked(LockedException ex) {
        log.warn("Próba logowania na zablokowane konto");
        return ApiProblem.of(HttpStatus.LOCKED, "Konto zablokowane",
                "Konto zostało tymczasowo zablokowane po zbyt wielu nieudanych próbach logowania. "
                        + "Spróbuj ponownie później.", "ACCOUNT_LOCKED");
    }

    /**
     * Konto zablokowane przez ADMINISTRATORA - inny stan niż blokada po nieudanych
     * logowaniach obsłużona wyżej.
     *
     * Handler stoi obok handleLocked i wygrywa z nim, bo Spring wybiera metodę
     * najbardziej szczegółową, a AccountBlockedException dziedziczy po LockedException.
     * To dziedziczenie jest tu fail-safe: gdyby ten handler kiedyś zniknął, odpowiedź
     * spadnie do 423 ACCOUNT_LOCKED zamiast do 500 - użytkownik dostanie gorszy komunikat,
     * ale konto pozostanie zamknięte.
     *
     * Kod ACCOUNT_BLOCKED musi być odrębny od ACCOUNT_LOCKED, bo front rozgałęzia się po
     * nim: blokada administracyjna nie mija sama i nie ma sensu radzić "spróbuj później".
     */
    @ExceptionHandler(AccountBlockedException.class)
    public ProblemDetail handleAccountBlocked(AccountBlockedException ex) {
        log.warn("Próba użycia konta zablokowanego przez administratora");
        return ApiProblem.of(HttpStatus.LOCKED, "Konto zablokowane",
                "Konto zostało zablokowane przez administratora. Skontaktuj się z obsługą.",
                "ACCOUNT_BLOCKED");
    }

    @ExceptionHandler(AdminUserNotFoundException.class)
    public ProblemDetail handleAdminUserNotFound(AdminUserNotFoundException ex) {
        log.warn("Panel administracyjny: nie znaleziono konta o podanym id");
        return ApiProblem.of(HttpStatus.NOT_FOUND, "Nie znaleziono",
                ex.getMessage(), "USER_NOT_FOUND");
    }

    /**
     * Akcja panelu odrzucona ze względu na stan systemu (blokada siebie, ostatni
     * administrator). 409, bo żądanie jest poprawne - to sytuacja po stronie serwera
     * czyni je niewykonalnym. Kod maszynowy przychodzi z wyjątku, bo przyczyny są dwie,
     * a front komunikuje je inaczej.
     */
    @ExceptionHandler(AdminActionRejectedException.class)
    public ProblemDetail handleAdminActionRejected(AdminActionRejectedException ex) {
        log.warn("Panel administracyjny: odrzucono akcję ({})", ex.getCode());
        return ApiProblem.of(HttpStatus.CONFLICT, "Akcja niedozwolona",
                ex.getMessage(), ex.getCode());
    }

    @ExceptionHandler(InvalidTokenException.class)
    public ProblemDetail handleInvalidToken(InvalidTokenException ex) {
        log.warn("Nieprawidłowy token: {}", ex.getMessage());
        return ApiProblem.of(HttpStatus.BAD_REQUEST, "Nieprawidłowy token", ex.getMessage(), "INVALID_TOKEN");
    }

    @ExceptionHandler(TokenExpiredException.class)
    public ProblemDetail handleTokenExpired(TokenExpiredException ex) {
        log.warn("Token wygasł: {}", ex.getMessage());
        return ApiProblem.of(HttpStatus.GONE, "Token wygasł", ex.getMessage(), "TOKEN_EXPIRED");
    }

    @ExceptionHandler(JwtAuthenticationException.class)
    public ProblemDetail handleJwtAuthentication(JwtAuthenticationException ex) {
        log.warn("Błąd JWT: {} - {}", ex.getTokenError(), ex.getMessage());
        // Maszynowy kod z wyjątku (EXPIRED_TOKEN / INVALID_TOKEN / INVALID_TOKEN_TYPE)
        // trafia wprost do pola "code", więc frontend wie, czy odświeżyć token, czy wylogować.
        return ApiProblem.of(HttpStatus.UNAUTHORIZED, "Błąd uwierzytelnienia",
                ex.getMessage(), ex.getTokenError());
    }

    /**
     * Rzucane przez zabezpieczenia na poziomie metod (@PreAuthorize).
     * Odmowy z filtrów Spring Security nie docierają tutaj - obsługuje je RestAccessDeniedHandler.
     * Bez tej metody wyjątek wpadłby w fallback poniżej i wrócił jako 500 zamiast 403.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail handleAccessDenied(AccessDeniedException ex) {
        log.warn("Odmowa dostępu: {}", ex.getMessage());
        return ApiProblem.of(HttpStatus.FORBIDDEN, "Brak uprawnień",
                "Nie masz uprawnień do tego zasobu", "ACCESS_DENIED");
    }

    /**
     * Wgrany plik nie nadaje się do tłumaczenia (pusty, złe rozszerzenie, złe kodowanie).
     * Kod maszynowy przychodzi z wyjątku, bo przyczyny są trzy, a odpowiedź jedna.
     */
    @ExceptionHandler(InvalidUploadException.class)
    public ProblemDetail handleInvalidUpload(InvalidUploadException ex) {
        log.warn("Odrzucono przesłany plik: {}", ex.getCode());
        return ApiProblem.of(HttpStatus.BAD_REQUEST, "Nieprawidłowy plik",
                ex.getMessage(), ex.getCode());
    }

    @ExceptionHandler(TranslationJobNotFoundException.class)
    public ProblemDetail handleTranslationJobNotFound(TranslationJobNotFoundException ex) {
        // Świadomie nie logujemy id: ta sama odpowiedź należy się zleceniu nieistniejącemu
        // i cudzemu, a rozróżnienie ich w logu zachęca do rozróżnienia ich w odpowiedzi.
        log.warn("Nie znaleziono zlecenia tłumaczenia dla bieżącego użytkownika");
        return ApiProblem.of(HttpStatus.NOT_FOUND, "Nie znaleziono",
                ex.getMessage(), "TRANSLATION_JOB_NOT_FOUND");
    }

    /**
     * Zlecenie istnieje, ale nie ma jeszcze wyniku. Status wraca w ciele, bo tylko on
     * mówi frontendowi, czy odpytywać dalej (PENDING/PROCESSING), czy przestać (FAILED).
     */
    @ExceptionHandler(TranslationNotReadyException.class)
    public ProblemDetail handleTranslationNotReady(TranslationNotReadyException ex) {
        ProblemDetail problem = ApiProblem.of(HttpStatus.CONFLICT, "Tłumaczenie niegotowe",
                ex.getMessage(), "TRANSLATION_NOT_READY");
        problem.setProperty("status", ex.getStatus());
        return problem;
    }

    /**
     * Wiersz zlecenia jest, ale jego pliku nie ma już w magazynie obiektowym.
     *
     * 410 GONE, a nie 404 ani 500, i każda z tych trzech odpowiedzi znaczyłaby co innego:
     * 404 kłamałoby, bo zlecenie istnieje i widać je na liście; 500 sugerowałoby awarię
     * do zgłoszenia, a to jest stan przewidziany.
     *
     * Ten stan jest ceną za to, że retencja żyje w DWÓCH miejscach: wiersze kasuje
     * TranslationCleanupJob według app.translation.retention, a pliki - reguła wygasania
     * na kubełku. Nic tych dwóch wartości nie wiąże poza uważnością, więc rozjazd musi mieć
     * objaw, po którym da się go rozpoznać. Powtarzające się CONTENT_EXPIRED przy zleceniach
     * młodszych niż retencja znaczy dokładnie jedno: reguła na kubełku kasuje za wcześnie.
     */
    @ExceptionHandler(ObjectMissingException.class)
    public ProblemDetail handleObjectMissing(ObjectMissingException ex) {
        log.warn("Plik zlecenia nie istnieje już w magazynie - sprawdź regułę wygasania "
                + "na kubełku wobec app.translation.retention");
        return ApiProblem.of(HttpStatus.GONE, "Plik niedostępny",
                "Plik nie jest już przechowywany - zleć tłumaczenie ponownie", "CONTENT_EXPIRED");
    }

    @ExceptionHandler(TranslationQuotaExceededException.class)
    public ProblemDetail handleTranslationQuota(TranslationQuotaExceededException ex) {
        ProblemDetail problem = ApiProblem.of(HttpStatus.TOO_MANY_REQUESTS, "Limit wyczerpany",
                ex.getMessage(), "TRANSLATION_QUOTA_EXCEEDED");
        problem.setProperty("remainingChars", ex.getRemainingChars());
        return problem;
    }

    /**
     * Siatka bezpieczeństwa na naruszenia więzów bazy (np. wyścig przy równoczesnej
     * rejestracji tego samego emaila, którego nie złapie wcześniejsze sprawdzenie).
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleDataIntegrity(DataIntegrityViolationException ex) {
        log.warn("Naruszenie więzów integralności: {}", constraintOf(ex));
        return ApiProblem.of(HttpStatus.CONFLICT, "Konflikt danych",
                "Operacja narusza ograniczenia bazy danych", "DATA_INTEGRITY_VIOLATION");
    }

    /**
     * Nazwa naruszonego więzu, a NIE komunikat sterownika.
     *
     * Komunikat sterownika niesie wartość, która kolizję wywołała, i dotyczy to każdego
     * silnika: PostgreSQL pisze "Key (email)=(ktos@example.com) already exists", MySQL pisał
     * "Duplicate entry 'ktos@example.com' for key 'users.email'". Najczęstszym wyzwalaczem
     * tego handlera jest właśnie wyścig przy rejestracji na ten sam adres, więc logowanie
     * komunikatu wprost wpisywałoby adresy użytkowników do logu produkcyjnego - poziom WARN
     * przechodzi przez prodowy próg. Nazwa więzu mówi diagnostycznie dokładnie tyle samo
     * ("wiadomo, który unikat pękł") i nie zawiera danych osobowych.
     */
    private String constraintOf(DataIntegrityViolationException ex) {
        // Przejście po całym łańcuchu, a nie getMostSpecificCause(): ta metoda schodzi do
        // NAJGŁĘBSZEJ przyczyny, czyli do SQLException sterownika, a ConstraintViolationException
        // Hibernate'a siedzi piętro wyżej. Sprawdzenie samego "najbardziej szczegółowego"
        // nigdy by go nie znalazło - i logowałoby dokładnie ten komunikat, którego tu unikamy.
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation
                    && violation.getConstraintName() != null) {
                return violation.getConstraintName();
            }
        }
        // Nieznany więz - zostaje sam typ przyczyny. Świadomie mniej informacji niż
        // w komunikacie: przy nierozpoznanym błędzie nie wiadomo, co ten komunikat niesie.
        return ex.getMostSpecificCause().getClass().getSimpleName();
    }

    /**
     * Błędy walidacji @Valid na ciele żądania.
     * Zwracamy listę błędów per pole zamiast sklejonego stringa - frontend może
     * podświetlić konkretne pola formularza zamiast wyświetlać jeden zlepek tekstu.
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
     * Przekroczony rozmiar wgrywanego pliku. Rzucane przy parsowaniu multiparta, czyli
     * PRZED wejściem do kontrolera - dlatego nie da się tego sprawdzić w walidacji uploadu.
     *
     * NADPISANIE metody bazowej, a nie własny @ExceptionHandler: ResponseEntityExceptionHandler
     * sam deklaruje obsługę tego wyjątku, więc drugie mapowanie na ten sam typ wywala
     * kontekst przy starcie ("Ambiguous @ExceptionHandler method mapped for..."). Ta sama
     * pułapka czeka przy każdym wyjątku, który Spring MVC już zna.
     *
     * 413, a nie 400: klient ma wiedzieć, że problem jest w rozmiarze, a nie w treści.
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
     * Nadpisane głównie dla targetLang: domyślna odpowiedź Springa mówi "nieprawidłowa
     * wartość" i nie zdradza, co wolno podać, więc użytkownik musi zgadywać albo szukać
     * w dokumentacji. Przy typie wyliczeniowym lista dopuszczalnych wartości jest znana
     * z definicji - nie ma powodu jej ukrywać.
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
     * Ostatnia linia obrony. Szczegóły lądują w logach, do klienta idzie ogólny komunikat -
     * stack trace czy komunikat z bazy w odpowiedzi HTTP to wyciek informacji o systemie.
     * Powiązanie odpowiedzi z logiem daje traceId dokładany przez ApiProblem.
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleGeneric(Exception ex) {
        log.error("Nieoczekiwany błąd", ex);
        return ApiProblem.of(HttpStatus.INTERNAL_SERVER_ERROR, "Błąd serwera",
                "Wewnętrzny błąd serwera", "INTERNAL_ERROR");
    }
}
