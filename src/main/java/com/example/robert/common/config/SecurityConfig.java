/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.robert.common.config;

import com.example.robert.common.security.CookieProperties;
import com.example.robert.common.security.JwtFilter;
import com.example.robert.common.security.JwtUtil;
import com.example.robert.common.observability.TraceIdFilter;
import com.example.robert.common.security.ProblemResponseWriter;
import com.example.robert.common.security.RestAccessDeniedHandler;
import com.example.robert.common.security.RestAuthenticationEntryPoint;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.ExceptionTranslationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

    private static final String[] PUBLIC_ENDPOINTS = {
            "/auth/**",
            // Dokumentacja API. Na profilu prod springdoc jest wyłączony w application-prod.yml,
            // więc te ścieżki po prostu nie istnieją.
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html",
            // Probe'y dla orkiestratora/load balancera - muszą działać bez uwierzytelnienia
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
     * JwtFilter tworzony jawnie jako bean (bez @Component), żeby Spring Boot nie
     * zarejestrował go dodatkowo jako zwykłego filtra serwletowego dla wszystkich żądań.
     */
    @Bean
    public JwtFilter jwtFilter(JwtUtil jwtUtil,
                               UserDetailsService userDetailsService,
                               ProblemResponseWriter problemWriter) {
        return new JwtFilter(jwtUtil, userDetailsService, problemWriter);
    }

    /**
     * Ochrona CSRF wariantem "double submit cookie", dostrojonym pod frontend
     * na innej domenie niż API.
     *
     * Dlaczego jest włączona: na produkcji ciasteczka lecą z SameSite=None, bo inaczej
     * przeglądarka w ogóle nie wysłałaby ich do API stojącego pod inną domeną niż SPA.
     * Ale SameSite był JEDYNĄ ochroną przed CSRF - poluzowanie go do None bez włączenia
     * tokenu oznaczałoby, że dowolna strona może wykonać żądanie zmieniające stan
     * na ciasteczkach ofiary. Jedno bez drugiego nie ma sensu.
     *
     * Ciasteczko XSRF-TOKEN zostaje httpOnly. To nie przeoczenie: skoro frontend jest
     * na innej domenie, jego JavaScript i tak nie odczytałby ciasteczka API, więc
     * klasyczny wariant z withHttpOnlyFalse() nic by nie dał. Frontend pobiera wartość
     * tokenu z GET /auth/csrf, trzyma ją w pamięci i odsyła w nagłówku X-XSRF-TOKEN;
     * przeglądarka dosyła ciasteczko sama, a serwer porównuje jedno z drugim.
     *
     * setCsrfRequestAttributeName(null) wyłącza leniwe ładowanie tokenu i kodowanie XOR,
     * dzięki czemu wartość w ciasteczku, w ciele GET /auth/csrf i w nagłówku to ten sam
     * ciąg. Bez tego token z ciała odpowiedzi nie zgadzałby się z zawartością ciasteczka.
     */
    private CsrfTokenRepository csrfTokenRepository() {
        CookieCsrfTokenRepository repository = new CookieCsrfTokenRepository();
        // Te same atrybuty co ciasteczka z tokenami - inaczej ciasteczko CSRF nie dotarłoby
        // tam, gdzie docierają tokeny, i ochrona wywalałaby poprawne żądania.
        repository.setCookieCustomizer(cookie -> cookie
                .secure(cookieProperties.secure())
                .sameSite(cookieProperties.sameSite())
                .path("/"));
        return repository;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtFilter jwtFilter) throws Exception {
        CsrfTokenRequestAttributeHandler csrfRequestHandler = new CsrfTokenRequestAttributeHandler();
        csrfRequestHandler.setCsrfRequestAttributeName(null);

        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokenRepository())
                        .csrfTokenRequestHandler(csrfRequestHandler))
                // Bez sesji HTTP - tożsamość niesie wyłącznie token w ciasteczku
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(ex -> ex
                        // 401 dla niezalogowanych, 403 dla zalogowanych bez uprawnień -
                        // w obu przypadkach w formacie ProblemDetail, tak jak reszta błędów API
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                )
                .authorizeHttpRequests(auth -> auth
                        /*
                         * MUSI stać przed regułą dla PUBLIC_ENDPOINTS - reguły dopasowywane
                         * są w kolejności deklaracji i wygrywa pierwsze trafienie, a
                         * PUBLIC_ENDPOINTS zawiera /auth/**, które objęłoby także tę ścieżkę.
                         *
                         * Skutek pominięcia nie byłby tylko taki, że "endpoint jest otwarty".
                         * JwtFilter działa niezależnie od reguł autoryzacji, więc dla
                         * zalogowanego wszystko wyglądałoby poprawnie, a anonim dostałby
                         * @AuthenticationPrincipal == null, czyli NPE i 500 zamiast czystego
                         * 401 - i to na jedynej ścieżce, o którą frontend pyta przy każdym
                         * starcie, żeby sprawdzić, czy sesja żyje.
                         *
                         * Zawężone do GET świadomie: /auth/me nie ma wariantu zmieniającego
                         * stan, więc reguła nie przykryje przypadkiem żadnego przyszłego POST-a.
                         */
                        .requestMatchers(HttpMethod.GET, "/auth/me").authenticated()
                        .requestMatchers(PUBLIC_ENDPOINTS).permitAll()
                        // Pozostałe endpointy Actuatora (metrics, prometheus) wystawiają
                        // dane operacyjne o systemie - tylko dla administratora.
                        .requestMatchers("/actuator/**").hasRole("ADMIN")
                        // Reguła zostaje, mimo że UserController został usunięty (obsługa
                        // użytkowników idzie na razie przez UserService i repozytorium).
                        // Domyślne "deny" jest właściwym stanem wyjściowym: gdy kontroler
                        // wróci, jego endpointy będą chronione od pierwszego commitu,
                        // a nie dopiero po tym, jak ktoś zauważy, że są otwarte.
                        .requestMatchers("/users/**").hasRole("ADMIN")
                        .anyRequest().authenticated()
                )
                /*
                 * JwtFilter stoi ZA SessionManagementFilter, a nie przed
                 * UsernamePasswordAuthenticationFilter - to decyzja o poprawności ochrony
                 * CSRF, nie kosmetyka kolejności.
                 *
                 * Włączony CSRF dokłada CsrfAuthenticationStrategy do strategii uwierzytelnienia
                 * (dokłada, nie zastępuje - podanie własnej strategii przez
                 * sessionAuthenticationStrategy() jej NIE wyłącza). Strategia ta przy każdym
                 * nowym uwierzytelnieniu kasuje token CSRF i wystawia nowy. W aplikacji z sesją
                 * dzieje się to raz, przy logowaniu, i chroni przed utrwaleniem tokenu. Tutaj
                 * sesji nie ma, więc SessionManagementFilter przy KAŻDYM żądaniu widzi
                 * uwierzytelnienie, którego "wcześniej nie było" - o ile JwtFilter zdążył je
                 * ustawić przed nim.
                 *
                 * Objaw był taki: frontend pobiera token z GET /auth/csrf, a serwer unieważnia
                 * mu go przy najbliższym żądaniu zalogowanego użytkownika - również przy zwykłym
                 * GET-cie. Pierwsze żądanie zmieniające stan jeszcze przechodziło, każde następne
                 * wracało z 403 CSRF_TOKEN_INVALID, a ponowne pobranie tokenu nie pomagało, bo
                 * nowy ginął tak samo. Wylogowanie i odświeżenie tokenu były nieosiągalne.
                 *
                 * Po przesunięciu SessionManagementFilter widzi jeszcze uwierzytelnienie
                 * anonimowe (ustawia je AnonymousAuthenticationFilter) i strategii nie odpala.
                 * JwtFilter podstawia właściwego użytkownika chwilę później, wciąż przed
                 * AuthorizationFilter, więc autoryzacja ścieżek działa bez zmian.
                 *
                 * Uwaga na testy: .with(csrf()) ze spring-security-test podmienia repozytorium
                 * tokenów i omija całą tę ścieżkę, więc tego błędu nie wykryje. Regresję pilnuje
                 * CsrfLifecycleTest, który przechodzi prawdziwą wymianę ciasteczko-nagłówek.
                 */
                .addFilterBefore(jwtFilter, ExceptionTranslationFilter.class);

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * AuthenticationManager budowany jawnie, zamiast pobierany z AuthenticationConfiguration.
     *
     * Powód: menedżer składany automatycznie nie miał ustawionego AuthenticationEventPublisher,
     * więc Spring Security nie publikował zdarzeń o nieudanym logowaniu - a to na nich opiera się
     * licznik prób i blokada konta (AuthenticationEventListener). Licznik po prostu nie rósł.
     *
     * Przy okazji cała konfiguracja uwierzytelniania jest tu widoczna wprost:
     * skąd brany jest użytkownik, czym weryfikowane jest hasło i co dzieje się ze zdarzeniami.
     */
    @Bean
    public AuthenticationManager authenticationManager(UserDetailsService userDetailsService,
                                                       PasswordEncoder passwordEncoder,
                                                       ApplicationEventPublisher applicationEventPublisher) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        // Domyślnie true: brak użytkownika zgłaszany jest jako BadCredentialsException,
        // czyli tak samo jak złe hasło. Zostawiamy - inaczej API pozwalałoby sprawdzać,
        // które adresy email są zarejestrowane.
        provider.setHideUserNotFoundExceptions(true);

        ProviderManager manager = new ProviderManager(provider);
        manager.setAuthenticationEventPublisher(new DefaultAuthenticationEventPublisher(applicationEventPublisher));
        return manager;
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        // Lista dozwolonych origins z konfiguracji (app.frontend.url), rozdzielona przecinkami.
        // Przy allowCredentials=true nie wolno użyć "*" - przeglądarka odrzuci taką odpowiedź.
        configuration.setAllowedOrigins(Arrays.stream(frontendOrigins.split(","))
                .map(String::trim)
                .toList());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        // Wymagane, żeby przeglądarka w ogóle wysyłała i przyjmowała nasze ciasteczka
        configuration.setAllowCredentials(true);
        // Bez tego JavaScript nie odczyta nagłówka korelacji z odpowiedzi
        configuration.setExposedHeaders(List.of(TraceIdFilter.TRACE_ID_HEADER));
        // Cache preflightu - mniej zapytań OPTIONS
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
