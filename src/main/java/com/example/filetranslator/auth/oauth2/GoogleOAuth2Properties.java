/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.filetranslator.auth.oauth2;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Dokąd na froncie odesłać przeglądarkę po logowaniu przez Google (prefiks app.oauth2).
 *
 * Osobno od app.frontend.url, bo tamto jest ORIGIN-em (używanym przez CORS i przy sklejaniu
 * linków w mailach), a to są TRASY w routingu SPA. Sklejenie obu w jedną właściwość
 * oznaczałoby, że zmiana trasy logowania na froncie wymaga ruszania konfiguracji CORS.
 *
 * @Validated, bo pusta trasa dałaby przekierowanie pod sam origin - czyli logowanie
 * kończyłoby się w losowym miejscu aplikacji, bez żadnego błędu po drodze.
 */
@Validated
@ConfigurationProperties(prefix = "app.oauth2")
public record GoogleOAuth2Properties(

        /** Trasa na froncie po UDANYM logowaniu. Sesja stoi już na ciasteczkach. */
        @NotBlank String successPath,

        /**
         * Trasa na froncie po NIEUDANYM logowaniu. Doklejany jest do niej parametr
         * error= z kodem, po którym front się rozgałęzia - tak samo, jak rozgałęzia się
         * po polu code z /auth/me.
         */
        @NotBlank String failurePath
) {
}
