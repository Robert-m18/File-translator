/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.filetranslator.auth.oauth2;

import com.example.filetranslator.common.security.CookieProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.security.jackson.SecurityJacksonModules;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;

/**
 * Przechowuje żądanie autoryzacyjne OAuth2 w ciasteczku zamiast w sesji HTTP.
 *
 * Implementacja jest konieczna, ponieważ domyślny mechanizm odkłada żądanie wraz z parametrami
 * zabezpieczającymi do sesji HTTP, a łańcuch filtrów tej aplikacji jest bezstanowy i sesji nie
 * tworzy. Powrót od dostawcy tożsamości kończyłby się wtedy błędem o nieodnalezionym żądaniu
 * autoryzacyjnym, który wskazuje na konfigurację klienta OAuth2, a więc nie na rzeczywistą
 * przyczynę.
 *
 * Atrybut SameSite jest przypięty do wartości Lax i celowo nie pochodzi z konfiguracji
 * ciasteczek aplikacji. Profil bazowy ustawia tam wartość ścisłą, a powrót od dostawcy jest
 * nawigacją międzywitrynową - przy ustawieniu ścisłym przeglądarka nie odesłałaby tego
 * ciasteczka i logowanie kończyłoby się tym samym błędem. Ciasteczko żyje kilka minut i jest
 * jednorazowe, więc wartość Lax jest właściwa niezależnie od środowiska.
 *
 * Atrybut Secure pochodzi natomiast z konfiguracji, bo na produkcji ciasteczko ma być wysyłane
 * wyłącznie po HTTPS, a lokalnie po zwykłym HTTP, inaczej przeglądarka je odrzuci. Wartość Lax
 * nie wymaga atrybutu Secure, więc obie decyzje są od siebie niezależne.
 *
 * Deserializacja treści ciasteczka korzysta z modułów Jackson dostarczanych przez
 * Spring Security (SecurityJacksonModules). Treść przychodzi od klienta, więc odtwarzanie
 * z niej dowolnego typu otwierałoby drogę do ataków deserializacyjnych - moduły te
 * konfigurują PolymorphicTypeValidator, który dopuszcza wyłącznie typy zarejestrowane
 * przez znane moduły bezpieczeństwa (m.in. OAuth2ClientJacksonModule), a nie dowolną
 * klasę ze ścieżki klas.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CookieOAuth2AuthorizationRequestRepository
        implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {

    static final String COOKIE_NAME = "oauth2_auth_request";

    /**
     * Tyle czasu ma użytkownik na ekran zgody Google. Krótko, bo to ciasteczko niesie
     * "state" - jedyną ochronę przed podrzuceniem cudzego logowania - a wygasłe żądanie
     * i tak da się rozpocząć od nowa jednym kliknięciem.
     */
    private static final Duration MAX_AGE = Duration.ofMinutes(3);

    private static final JsonMapper OBJECT_MAPPER = JsonMapper.builder()
            .addModules(SecurityJacksonModules.getModules(
                    CookieOAuth2AuthorizationRequestRepository.class.getClassLoader()))
            .build();

    private final CookieProperties cookieProperties;

    @Override
    public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
        return readCookie(request).flatMap(this::deserialize).orElse(null);
    }

    @Override
    public void saveAuthorizationRequest(OAuth2AuthorizationRequest authorizationRequest,
                                         HttpServletRequest request,
                                         HttpServletResponse response) {
        // null = Spring Security prosi o usunięcie żądania (np. przy ponownym starcie
        // przepływu). Kontrakt interfejsu, nie przypadek brzegowy.
        if (authorizationRequest == null) {
            clear(response);
            return;
        }
        response.addHeader(HttpHeaders.SET_COOKIE, cookie(serialize(authorizationRequest), MAX_AGE).toString());
    }

    /**
     * Zdejmuje żądanie i KASUJE ciasteczko przy okazji.
     *
     * Kasowanie jest tutaj, a nie w obsłudze sukcesu, bo ta metoda jest wołana na obu
     * zakończeniach przepływu - udanym i nieudanym. Gdyby ciasteczko zostawało, kolejna
     * próba logowania startowałaby z cudzym "state" i odbijała się bez zrozumiałego powodu.
     */
    @Override
    public OAuth2AuthorizationRequest removeAuthorizationRequest(HttpServletRequest request,
                                                                 HttpServletResponse response) {
        OAuth2AuthorizationRequest authorizationRequest = loadAuthorizationRequest(request);
        clear(response);
        return authorizationRequest;
    }

    private void clear(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString());
    }

    /**
     * Kasowanie ciasteczka to to samo ciasteczko z maxAge=0 - musi mieć identyczną nazwę,
     * ścieżkę i atrybuty, inaczej przeglądarka uzna je za inne i starego nie usunie.
     * Ta sama zasada co w CookieService.build.
     */
    private ResponseCookie cookie(String value, Duration maxAge) {
        return ResponseCookie.from(COOKIE_NAME, value)
                .httpOnly(true)
                .secure(cookieProperties.secure())
                .sameSite("Lax")
                .path("/")
                .maxAge(maxAge)
                .build();
    }

    private Optional<String> readCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        return Arrays.stream(cookies)
                .filter(cookie -> COOKIE_NAME.equals(cookie.getName()))
                .map(Cookie::getValue)
                .filter(value -> value != null && !value.isBlank())
                .findFirst();
    }

    private String serialize(OAuth2AuthorizationRequest authorizationRequest) {
        String json = OBJECT_MAPPER.writeValueAsString(authorizationRequest);
        return Base64.getUrlEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Zwraca pusty Optional dla KAŻDEJ nieprawidłowej treści - uszkodzonej, obcej,
     * wygasłej czy odrzuconej przez filtr.
     *
     * Świadomie bez rzucania wyjątkiem: to jest wejście sterowane przez klienta i jedyna
     * sensowna reakcja na śmieci w ciasteczku to potraktowanie ich jak braku ciasteczka.
     * Spring Security odpowie wtedy swoim authorization_request_not_found, czyli tak samo,
     * jak gdyby użytkownik po prostu wszedł na adres powrotny z palca.
     */
    private Optional<OAuth2AuthorizationRequest> deserialize(String value) {
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(value);
            String json = new String(decoded, StandardCharsets.UTF_8);
            return Optional.of(OBJECT_MAPPER.readValue(json, OAuth2AuthorizationRequest.class));
        } catch (IllegalArgumentException | JacksonException e) {
            log.debug("Odrzucono nieprawidłowe ciasteczko żądania autoryzacyjnego OAuth2: {}",
                    e.getClass().getSimpleName());
            return Optional.empty();
        }
    }
}
