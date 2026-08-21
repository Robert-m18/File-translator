/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.filetranslator.common.config;

import com.example.filetranslator.auth.oauth2.CookieOAuth2AuthorizationRequestRepository;
import com.example.filetranslator.auth.oauth2.GoogleOAuth2FailureHandler;
import com.example.filetranslator.auth.oauth2.GoogleOAuth2SuccessHandler;
import com.example.filetranslator.auth.oauth2.GoogleOidcUserService;
import com.example.filetranslator.common.security.BlockedAccountChecker;
import com.example.filetranslator.common.security.CookieProperties;
import com.example.filetranslator.common.security.JwtFilter;
import com.example.filetranslator.common.security.JwtUtil;
import com.example.filetranslator.common.observability.TraceIdFilter;
import com.example.filetranslator.common.security.ProblemResponseWriter;
import com.example.filetranslator.common.security.RestAccessDeniedHandler;
import com.example.filetranslator.common.security.RestAuthenticationEntryPoint;
import com.example.filetranslator.common.security.RateLimitFilter;
import com.example.filetranslator.common.security.RateLimitProperties;
import com.example.filetranslator.common.security.ratelimit.BucketProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.DefaultAuthenticationEventPublisher;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.ExceptionTranslationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * Konfiguracja bezpieczeństwa: bezstanowy łańcuch filtrów, autoryzacja po ścieżkach, CORS,
 * CSRF oraz opcjonalne logowanie przez Google.
 *
 * Tożsamość niesie wyłącznie token JWT w ciasteczku httpOnly - nie ma sesji HTTP ani nagłówka
 * Authorization. Dzięki temu uwierzytelnienie nie wymaga stanu po stronie serwera i skaluje się
 * na wiele instancji, a token pozostaje niedostępny dla JavaScriptu.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

    private static final String[] PUBLIC_ENDPOINTS = {
            "/auth/**",
            /*
             * Logowanie przez Google: /oauth2/authorization/{id} rozpoczyna przepływ,
             * /login/oauth2/code/{id} jest adresem powrotnym od dostawcy. Obie ścieżki muszą być
             * publiczne, ponieważ użytkownik dopiero się na nich uwierzytelnia - objęte regułą
             * anyRequest().authenticated() kończyłyby się odpowiedzią 401, czyli logowanie
             * wymagałoby bycia zalogowanym.
             */
            "/oauth2/**",
            "/login/oauth2/**",
            // Dokumentacja API. Na profilu prod springdoc jest wyłączony w application-prod.yml,
            // więc te ścieżki tam nie istnieją.
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html",
            // Probe'y dla orkiestratora i load balancera - muszą działać bez uwierzytelnienia.
            "/actuator/health",
            "/actuator/health/**",
            "/actuator/info"
    };

    private final RestAuthenticationEntryPoint authenticationEntryPoint;
    private final RestAccessDeniedHandler accessDeniedHandler;
    private final CookieProperties cookieProperties;

    @Value("${app.frontend.url}")
    private String frontendOrigins;

    /**
     * Tworzy filtr JWT jawnie jako bean, bez adnotacji @Component. Filtr oznaczony @Component
     * zostałby dodatkowo zarejestrowany jako zwykły filtr serwletowy i działał również poza
     * łańcuchem Spring Security, także na ścieżkach świadomie z niego wyłączonych.
     */
    @Bean
    public JwtFilter jwtFilter(JwtUtil jwtUtil,
                               UserDetailsService userDetailsService,
                               ProblemResponseWriter problemWriter) {
        return new JwtFilter(jwtUtil, userDetailsService, problemWriter);
    }

    @Bean
    public RateLimitFilter rateLimitFilter(RateLimitProperties rateLimitProperties,
                                           ProblemResponseWriter problemWriter,
                                           BucketProvider bucketProvider) {
        return new RateLimitFilter(rateLimitProperties, problemWriter, bucketProvider);
    }

    /**
     * Wyłącza rejestrację filtra limitera w kontenerze serwletów.
     *
     * Spring Boot rejestruje każdy bean typu Filter jako filtr serwletowy niezależnie od tego,
     * czy powstał przez @Component, czy przez @Bean, i niezależnie od jawnego dodania go do
     * łańcucha Spring Security. Bez tej rejestracji filtr wykonywałby się dwa razy na żądanie,
     * a więc każde żądanie zabierałoby z kubełka dwa żetony zamiast jednego i rzeczywisty próg
     * limitu byłby połową skonfigurowanego - bez żadnego widocznego objawu.
     */
    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(RateLimitFilter filter) {
        FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    /** Ten sam mechanizm co wyżej, dla filtra JWT: bez niego uwierzytelnianie biegłoby dwa razy. */
    @Bean
    public FilterRegistrationBean<JwtFilter> jwtFilterRegistration(JwtFilter filter) {
        FilterRegistrationBean<JwtFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    /**
     * Buduje repozytorium tokenów CSRF w wariancie "double submit cookie", dostrojonym pod
     * frontend stojący na innej domenie niż API.
     *
     * Ochrona jest włączona, ponieważ na produkcji ciasteczka wymagają SameSite=None - inaczej
     * przeglądarka nie wysłałaby ich do API pod inną domeną. SameSite był jednak jedyną ochroną
     * przed CSRF, więc poluzowanie go bez tokenu pozwoliłoby dowolnej stronie wykonywać żądania
     * zmieniające stan na ciasteczkach ofiary. Te dwie decyzje są nierozłączne.
     *
     * Ciasteczko XSRF-TOKEN pozostaje httpOnly: skoro frontend jest na innej domenie, jego
     * JavaScript i tak nie odczytałby ciasteczka API, więc wariant withHttpOnlyFalse() nie dałby
     * nic poza osłabieniem ochrony. Frontend pobiera wartość tokenu z GET /auth/csrf, trzyma ją
     * w pamięci i odsyła w nagłówku, a przeglądarka dosyła ciasteczko sama.
     */
    private CsrfTokenRepository csrfTokenRepository() {
        CookieCsrfTokenRepository repository = new CookieCsrfTokenRepository();
        // Te same atrybuty co ciasteczka z tokenami - inaczej ciasteczko CSRF nie dotarłoby tam,
        // gdzie docierają tokeny, i ochrona odrzucałaby poprawne żądania.
        repository.setCookieCustomizer(cookie -> cookie
                .secure(cookieProperties.secure())
                .sameSite(cookieProperties.sameSite())
                .path("/"));
        return repository;
    }

    /**
     * Wpina logowanie przez Google, ale wyłącznie wtedy, gdy klient OAuth2 jest skonfigurowany.
     *
     * Bez konfiguracji klienta bean ClientRegistrationRepository nie powstaje, a bezwarunkowe
     * wywołanie oauth2Login() przerwałoby start aplikacji. Warunek sprawia, że brak konfiguracji
     * daje aplikację działającą z mniejszą liczbą funkcji zamiast aplikacji martwej - ta sama
     * zasada, dla której domyślnym dostawcą tłumaczenia jest atrapa.
     *
     * Wyłączenie funkcji jest sygnalizowane ostrzeżeniem w logu, ponieważ cicha nieobecność
     * funkcji jest trudna do zdiagnozowania: aplikacja wstaje, wygląda zdrowo i po prostu nie
     * robi tego, czego się po niej oczekuje.
     *
     * CSRF nie wymaga tu wyjątku - obie ścieżki OAuth2 obsługują metodę GET, a CsrfFilter
     * obejmuje wyłącznie metody zmieniające stan.
     */
    private void configureGoogleLogin(HttpSecurity http,
                                      ObjectProvider<ClientRegistrationRepository> clientRegistrations,
                                      GoogleOidcUserService oidcUserService,
                                      GoogleOAuth2SuccessHandler successHandler,
                                      GoogleOAuth2FailureHandler failureHandler,
                                      CookieOAuth2AuthorizationRequestRepository authorizationRequestRepository)
            throws Exception {

        if (clientRegistrations.getIfAvailable() == null) {
            log.warn("Logowanie przez Google jest WYŁĄCZONE - brak konfiguracji klienta OAuth2. "
                    + "Ustaw GOOGLE_CLIENT_ID i GOOGLE_CLIENT_SECRET, żeby je włączyć.");
            return;
        }

        http.oauth2Login(oauth2 -> oauth2
                // Żądanie autoryzacyjne trafia do ciasteczka, nie do sesji - łańcuch jest
                // STATELESS, więc domyślne repozytorium sesyjne nie miałoby gdzie go odłożyć.
                .authorizationEndpoint(endpoint -> endpoint
                        .authorizationRequestRepository(authorizationRequestRepository))
                // Kontrole (potwierdzony adres, blokada konta) znajdują się w serwisie, a nie
                // w handlerze sukcesu: tylko stamtąd odmowa trafia do handlera porażki.
                .userInfoEndpoint(endpoint -> endpoint.oidcUserService(oidcUserService))
                .successHandler(successHandler)
                .failureHandler(failureHandler));
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           JwtFilter jwtFilter,
                                           RateLimitFilter rateLimitFilter,
                                           ObjectProvider<ClientRegistrationRepository> clientRegistrations,
                                           GoogleOidcUserService oidcUserService,
                                           GoogleOAuth2SuccessHandler oauth2SuccessHandler,
                                           GoogleOAuth2FailureHandler oauth2FailureHandler,
                                           CookieOAuth2AuthorizationRequestRepository authorizationRequestRepository)
            throws Exception {
        CsrfTokenRequestAttributeHandler csrfRequestHandler = new CsrfTokenRequestAttributeHandler();
        // Wyłącza leniwe ładowanie tokenu i kodowanie XOR, dzięki czemu wartość w ciasteczku,
        // w ciele GET /auth/csrf i w nagłówku to ten sam ciąg. Bez tego token z ciała odpowiedzi
        // nie zgadzałby się z zawartością ciasteczka.
        csrfRequestHandler.setCsrfRequestAttributeName(null);

        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokenRepository())
                        .csrfTokenRequestHandler(csrfRequestHandler))
                // Bez sesji HTTP - tożsamość niesie wyłącznie token w ciasteczku.
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(ex -> ex
                        // 401 dla niezalogowanych, 403 dla zalogowanych bez uprawnień - w obu
                        // przypadkach w formacie ProblemDetail, tak jak reszta błędów API.
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                )
                .authorizeHttpRequests(auth -> auth
                        /*
                         * Reguła musi stać przed PUBLIC_ENDPOINTS: dopasowanie następuje
                         * w kolejności deklaracji i wygrywa pierwsze trafienie, a PUBLIC_ENDPOINTS
                         * zawiera /auth/**, które objęłoby także tę ścieżkę.
                         *
                         * Konsekwencja odwrotnej kolejności jest gorsza niż samo otwarcie
                         * endpointu: JwtFilter działa niezależnie od reguł autoryzacji, więc
                         * zalogowany nie zauważyłby różnicy, a żądanie anonimowe dotarłoby do
                         * kontrolera z pustym @AuthenticationPrincipal i skończyło się odpowiedzią
                         * 500 zamiast czystego 401 - na jedynej ścieżce, o którą frontend pyta
                         * przy każdym starcie, sprawdzając, czy sesja żyje.
                         *
                         * Zawężenie do GET jest celowe: /auth/me nie ma wariantu zmieniającego
                         * stan, więc reguła nie przykryje przypadkiem przyszłej metody POST.
                         */
                        .requestMatchers(HttpMethod.GET, "/auth/me").authenticated()
                        .requestMatchers(PUBLIC_ENDPOINTS).permitAll()
                        // Pozostałe endpointy Actuatora (metrics, prometheus) wystawiają dane
                        // operacyjne o systemie - wyłącznie dla administratora.
                        .requestMatchers("/actuator/**").hasRole("ADMIN")
                        // Panel administracyjny. Reguła obowiązuje niezależnie od tego, jakie
                        // endpointy są pod nią zmapowane, dzięki czemu każdy nowy kontroler w tej
                        // przestrzeni jest chroniony od pierwszego commitu, a nie dopiero po
                        // zauważeniu, że jest otwarty.
                        .requestMatchers("/users/**").hasRole("ADMIN")
                        // Zlecenia tłumaczenia - wyłącznie dla zalogowanych. Reguła jest jawna,
                        // choć anyRequest() poniżej i tak by ją pokryło: przynależność zasobu do
                        // użytkownika ma być widoczna w konfiguracji bezpieczeństwa, a nie wynikać
                        // z domyślnego zachowania. Dostęp do cudzych zleceń odcina warunek na
                        // user_id w zapytaniach - autoryzacji per wiersz nie da się wyrazić
                        // matcherem po ścieżce.
                        .requestMatchers("/translations/**").authenticated()
                        .anyRequest().authenticated()
                )
                /*
                 * Pozycja filtra JWT za SessionManagementFilter jest warunkiem działania ochrony
                 * CSRF, a nie kwestią porządku.
                 *
                 * Włączony CSRF dokłada do strategii uwierzytelnienia CsrfAuthenticationStrategy
                 * (dokłada, nie zastępuje - podanie własnej strategii jej nie wyłącza), która przy
                 * każdym nowym uwierzytelnieniu kasuje token CSRF i wystawia nowy. W aplikacji
                 * z sesją dzieje się to raz, przy logowaniu, i chroni przed utrwaleniem tokenu.
                 * Tutaj sesji nie ma, więc przy filtrze ustawionym wcześniej SessionManagementFilter
                 * widziałby nowe uwierzytelnienie przy każdym żądaniu i unieważniał token, również
                 * przy zwykłym GET. Skutkiem byłoby 403 CSRF_TOKEN_INVALID na każdym żądaniu
                 * zmieniającym stan poza pierwszym, przy czym ponowne pobranie tokenu nie pomaga,
                 * bo nowy ginie tak samo.
                 *
                 * Przy tej pozycji SessionManagementFilter widzi jeszcze uwierzytelnienie anonimowe
                 * i strategii nie uruchamia, a JwtFilter podstawia właściwego użytkownika chwilę
                 * później, wciąż przed AuthorizationFilter.
                 */
                .addFilterBefore(jwtFilter, ExceptionTranslationFilter.class)
                /*
                 * Limiter tuż za CorsFilter. Umieszczony przed nim odpowiadałby kodem 429 bez
                 * nagłówków CORS, a taką odpowiedź przeglądarka blokuje - frontend na innym origin
                 * widziałby błąd sieci zamiast komunikatu o przekroczeniu limitu.
                 *
                 * Pozycja pozostaje przed CsrfFilter i przed uwierzytelnianiem, więc odrzucone
                 * żądanie nie kosztuje ani zapytania do bazy, ani wyliczenia BCrypt. Świadomie
                 * przyjęty skutek uboczny: żądania odrzucone przez CSRF również zużywają żetony.
                 */
                .addFilterAfter(rateLimitFilter, CorsFilter.class);

        configureGoogleLogin(http, clientRegistrations, oidcUserService,
                oauth2SuccessHandler, oauth2FailureHandler, authorizationRequestRepository);

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Buduje AuthenticationManager jawnie, zamiast pobierać go z AuthenticationConfiguration.
     *
     * Menedżer składany automatycznie nie ma ustawionego AuthenticationEventPublisher, więc
     * Spring Security nie publikuje zdarzeń o nieudanym logowaniu - a to na nich opiera się
     * licznik prób i blokada konta. Dodatkową korzyścią jest to, że cała konfiguracja
     * uwierzytelniania jest widoczna w jednym miejscu: skąd pochodzi użytkownik, czym
     * weryfikowane jest hasło i co dzieje się ze zdarzeniami.
     */
    @Bean
    public AuthenticationManager authenticationManager(UserDetailsService userDetailsService,
                                                       PasswordEncoder passwordEncoder,
                                                       ApplicationEventPublisher applicationEventPublisher) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        // Blokada administracyjna odsiewana przed porównaniem hasła, z własnym kodem
        // ACCOUNT_BLOCKED zamiast ogólnego ACCOUNT_LOCKED, który sugerowałby blokadę mijającą
        // samoczynnie. Checker deleguje do domyślnych sprawdzeń stanu konta.
        provider.setPreAuthenticationChecks(new BlockedAccountChecker());
        // Brak użytkownika zgłaszany jest jako BadCredentialsException, czyli tak samo jak złe
        // hasło. Dzięki temu API nie pozwala sprawdzić, które adresy są zarejestrowane.
        provider.setHideUserNotFoundExceptions(true);

        ProviderManager manager = new ProviderManager(provider);
        manager.setAuthenticationEventPublisher(new DefaultAuthenticationEventPublisher(applicationEventPublisher));
        return manager;
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        // Lista dozwolonych origin z konfiguracji (app.frontend.url), rozdzielona przecinkami.
        // Przy allowCredentials=true gwiazdka jest niedozwolona - przeglądarka odrzuci odpowiedź.
        configuration.setAllowedOrigins(Arrays.stream(frontendOrigins.split(","))
                .map(String::trim)
                .toList());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        // Wymagane, żeby przeglądarka wysyłała i przyjmowała ciasteczka tej aplikacji.
        configuration.setAllowCredentials(true);
        /*
         * Przeglądarka udostępnia JavaScriptowi tylko nagłówki wymienione tutaj; pozostałe są dla
         * niego niewidoczne, mimo że dotarły w odpowiedzi. Domyślna lista obejmuje garstkę
         * nagłówków prostych i nie zawiera Content-Disposition.
         *
         * Pominięcie daje objaw cichy i mylący, bo żądanie kończy się powodzeniem: pobranie
         * przetłumaczonego pliku działa, tylko frontend nie odczyta zaproponowanej nazwy i zapisze
         * plik pod nazwą awaryjną.
         *
         * Nagłówek z identyfikatorem żądania jest wystawiony z tego samego powodu: bez niego
         * użytkownik zgłaszający błąd nie ma czego podać, żeby dało się odnaleźć jego żądanie
         * w logach.
         */
        configuration.setExposedHeaders(List.of(
                TraceIdFilter.TRACE_ID_HEADER,
                HttpHeaders.CONTENT_DISPOSITION));
        // Cache preflightu - ogranicza liczbę żądań OPTIONS.
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
