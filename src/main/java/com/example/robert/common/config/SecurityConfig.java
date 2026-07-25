/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.robert.common.config;

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
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
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

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtFilter jwtFilter) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                // CSRF wyłączone świadomie: API jest bezstanowe, a ochronę przy
                // uwierzytelnianiu na ciasteczkach zapewnia atrybut SameSite.
                // UWAGA: przy dokładaniu logowania przez OAuth2 SameSite trzeba będzie
                // zluzować do Lax, a wtedy CSRF wymaga osobnego zabezpieczenia.
                .csrf(csrf -> csrf.disable())
                // Bez sesji HTTP - tożsamość niesie wyłącznie token w ciasteczku
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(ex -> ex
                        // 401 dla niezalogowanych, 403 dla zalogowanych bez uprawnień -
                        // w obu przypadkach w formacie ProblemDetail, tak jak reszta błędów API
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                )
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_ENDPOINTS).permitAll()
                        // Pozostałe endpointy Actuatora (metrics, prometheus) wystawiają
                        // dane operacyjne o systemie - tylko dla administratora.
                        .requestMatchers("/actuator/**").hasRole("ADMIN")
                        .requestMatchers("/users/**").hasRole("ADMIN")
                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

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
