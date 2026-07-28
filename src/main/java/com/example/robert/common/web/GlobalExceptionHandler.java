/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.robert.common.web;

import com.example.robert.common.exception.EmailAlreadyExistException;
import com.example.robert.common.exception.InvalidTokenException;
import com.example.robert.common.exception.JwtAuthenticationException;
import com.example.robert.common.exception.NotFoundException;
import com.example.robert.common.exception.TokenExpiredException;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.exception.ConstraintViolationException;
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

    @ExceptionHandler(NotFoundException.class)
    public ProblemDetail handleNotFound(NotFoundException ex) {
        log.warn("Zasób nie znaleziony: {}", ex.getMessage());
        return ApiProblem.of(HttpStatus.NOT_FOUND, "Nie znaleziono", ex.getMessage(), "RESOURCE_NOT_FOUND");
    }

    @ExceptionHandler(EmailAlreadyExistException.class)
    public ProblemDetail handleEmailExists(EmailAlreadyExistException ex) {
        log.warn("Konflikt adresu email");
        return ApiProblem.of(HttpStatus.CONFLICT, "Konflikt", ex.getMessage(), "EMAIL_ALREADY_EXISTS");
    }

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
     * Komunikat MySQL-a niesie wartość, która kolizję wywołała:
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
