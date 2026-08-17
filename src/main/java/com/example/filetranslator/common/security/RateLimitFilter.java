/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.filetranslator.common.security;

import com.example.filetranslator.common.security.ratelimit.BucketProvider;
import com.example.filetranslator.common.web.ApiProblem;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

/**
 * Ogranicza liczbę żądań na wrażliwych ścieżkach (logowanie, rejestracja, odświeżanie).
 *
 * Po co: bez tego atak siłowy na hasło jest ograniczony wyłącznie przepustowością łącza
 * napastnika, a endpoint rejestracji pozwala zalać dowolną skrzynkę mailami albo
 * wyczerpać limit u dostawcy poczty. Blokada konta chroni pojedyncze konto, ten filtr
 * chroni serwer i pozostałych użytkowników.
 *
 * Algorytm to token bucket (bucket4j): każdy klucz dostaje pulę żetonów odnawianą co okno
 * czasowe. W odróżnieniu od sztywnego licznika na okno znosi naturalne skoki ruchu,
 * nie przepuszczając przy tym podwójnej porcji żądań na styku dwóch okien.
 *
 * OGRANICZENIE: kubełki trzymane są w pamięci procesu, więc przy kilku instancjach
 * aplikacji każda liczy limit osobno. Do wdrożenia wieloinstancyjnego trzeba podmienić
 * magazyn na Redis (bucket4j-redis) - reszta tej klasy zostaje bez zmian.
 *
 * Filtr działa w łańcuchu Spring Security, ale TUŻ ZA CorsFilter i przed uwierzytelnianiem:
 * odrzucone żądanie nie kosztuje ani zapytania do bazy, ani porównania hasha BCrypt, czyli
 * dokładnie tego, co przy ataku siłowym jest najdroższe.
 *
 * Ta pozycja to naprawa konkretnego błędu, nie porządkowanie kolejności. Wcześniej filtr
 * stał PRZED całym łańcuchem (@Component + @Order(HIGHEST_PRECEDENCE + 10)), czyli również
 * przed CorsFilter - a wtedy odpowiedź 429 wychodziła BEZ nagłówka Access-Control-Allow-Origin.
 * Przeglądarka blokuje taką odpowiedź, więc frontend na innym origin nie dostawał komunikatu
 * "spróbuj ponownie za X s", tylko błąd sieci od fetch(): dla użytkownika wyglądało to jak
 * padnięty serwer, a nie jak przekroczony limit. Treść odpowiedzi była poprawna od zawsze -
 * po prostu nigdy nie docierała. Sprawdzone 2026-08-04 curl-em z nagłówkiem Origin,
 * regresję pilnuje RateLimitTest.
 *
 * Filtr CORS jest tani (kilka porównań nagłówków), więc przesunięcie za niego nie psuje
 * zasady, dla której limiter stoi wcześnie.
 */
@Slf4j
public class RateLimitFilter extends OncePerRequestFilter {

    private static final String HEADER_REMAINING = "X-RateLimit-Remaining";

    private final RateLimitProperties properties;
    private final ProblemResponseWriter problemWriter;
    /** Magazyn kubełków: pamięć procesu albo Redis - wybór przez app.rate-limit.store. */
    private final BucketProvider bucketProvider;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public RateLimitFilter(RateLimitProperties properties,
                           ProblemResponseWriter problemWriter,
                           BucketProvider bucketProvider) {
        this.properties = properties;
        this.problemWriter = problemWriter;
        this.bucketProvider = bucketProvider;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) throws ServletException {
        return !properties.enabled() || findPolicy(request).isEmpty();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        RateLimitProperties.Policy policy = findPolicy(request).orElseThrow();

        // Metoda jest częścią klucza, inaczej dwie reguły na tej samej ścieżce (np. osobny
        // próg dla POST /translations i dla GET /translations) dzieliłyby JEDEN kubełek -
        // i ta o mniejszej pojemności odcinałaby ruch objęty tą drugą.
        String scope = policy.method() == null || policy.method().isBlank()
                ? "*" : policy.method().toUpperCase(Locale.ROOT);
        String key = policy.path() + "|" + scope + "|" + clientIp(request);

        Bucket bucket = bucketProvider.resolveBucket(key, policy);
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);

        if (probe.isConsumed()) {
            response.setHeader(HEADER_REMAINING, String.valueOf(probe.getRemainingTokens()));
            filterChain.doFilter(request, response);
            return;
        }

        long retryAfterSeconds = Math.max(1, probe.getNanosToWaitForRefill() / 1_000_000_000L);

        // Adres IP w logu jest tu WYJĄTKIEM od zasady "nie logujemy danych osobowych"
        // i wyjątkiem świadomym. To jedyny log o nadużyciu, a bez adresu nie da się na nim
        // niczego zrobić: ani odróżnić jednego bota od tysiąca użytkowników za NAT-em, ani
        // zablokować źródła. Zdjęcie IP stąd zamienia ten log w licznik bez treści -
        // gdyby ktoś chciał to "poprawić", to jest powód, dla którego zostaje.
        log.warn("Przekroczono limit żądań dla {} (ścieżka {}), ponowna próba za {}s",
                clientIp(request), policy.path(), retryAfterSeconds);

        // Retry-After to standardowy nagłówek dla 429 - poprawnie napisany klient
        // odczeka podany czas zamiast dobijać serwer w pętli.
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds));
        response.setHeader(HEADER_REMAINING, "0");

        ProblemDetail problem = ApiProblem.of(
                HttpStatus.TOO_MANY_REQUESTS,
                "Zbyt wiele żądań",
                "Przekroczono limit żądań. Spróbuj ponownie za " + retryAfterSeconds + " s.",
                "RATE_LIMIT_EXCEEDED");
        problem.setProperty("retryAfterSeconds", retryAfterSeconds);

        problemWriter.write(response, problem);
    }

    private Optional<RateLimitProperties.Policy> findPolicy(HttpServletRequest request) {
        if (properties.policies() == null) {
            return Optional.empty();
        }
        String path = request.getRequestURI();
        return properties.policies().stream()
                .filter(policy -> pathMatcher.match(policy.path(), path))
                // Metoda sprawdzana obok ścieżki, bo koszt żądania potrafi się między nimi
                // różnić o rzędy wielkości: POST /translations woła płatne API dostawcy,
                // GET tej samej ścieżki czyta wiersz z bazy. Reguła bez metody obejmuje
                // wszystkie - tak działają wszystkie reguły dla /auth/**.
                .filter(policy -> policy.matchesMethod(request.getMethod()))
                .findFirst();
    }

    /**
     * Za reverse proxy prawdziwy adres klienta niesie nagłówek X-Forwarded-For.
     * Podstawia go pod getRemoteAddr() filtr ForwardedHeaderFilter, włączany przez
     * server.forward-headers-strategy=framework (ustawione na profilu prod).
     * Ręczne czytanie tego nagłówka byłoby błędem - klient może go dowolnie podrobić,
     * jeśli nie stoi za zaufanym proxy.
     */
    private String clientIp(HttpServletRequest request) {
        String ip = request.getRemoteAddr();
        return ip == null ? "unknown" : ip;
    }
}
