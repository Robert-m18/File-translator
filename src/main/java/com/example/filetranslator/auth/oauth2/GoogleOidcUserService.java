/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.filetranslator.auth.oauth2;

import com.example.filetranslator.common.validation.EmailNormalizer;
import com.example.filetranslator.user.UserService;
import com.example.filetranslator.user.model.User;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Zamienia tożsamość potwierdzoną przez Google na konto w tej aplikacji.
 *
 * TO JEST MIEJSCE NA KONTROLE BEZPIECZEŃSTWA, a nie handler sukcesu - i to nie jest kwestia
 * gustu. Wyjątek rzucony stąd jest wyjątkiem uwierzytelnienia, więc Spring Security kieruje
 * go do handlera PORAŻKI, który odsyła użytkownika na front z kodem błędu. Handler sukcesu
 * nie ma jak odmówić: w jego sygnaturze nie ma wyjątku uwierzytelnienia, a użytkownik jest
 * na tym etapie już uwierzytelniony.
 *
 * DWIE KONTROLE, KTÓRYCH NIE ROBI ZA NAS NIKT INNY:
 *
 * 1. POTWIERDZENIE ADRESU. Bez roszczenia email_verified = true adres z tokenu ID jest
 *    tylko napisem. Ponieważ to WŁAŚNIE po adresie łączymy konto Google z istniejącym
 *    kontem hasłowym (UserService.findOrCreateGoogleUser), przepuszczenie niepotwierdzonego
 *    adresu zamieniłoby łączenie kont w ich PRZEJMOWANIE: wystarczyłoby założyć konto Google
 *    na cudzy adres.
 *
 * 2. BLOKADA ADMINISTRACYJNA. Logowanie przez OAuth2 OMIJA W CAŁOŚCI DaoAuthenticationProvider,
 *    a więc i BlockedAccountChecker, i isAccountNonLocked(). Bez tej linijki blokada po prostu
 *    NIE DZIAŁA na ścieżce Google: zablokowany dostaje 423 przy logowaniu hasłem i wchodzi
 *    bokiem przez Google. Ten sam kształt błędu ta aplikacja miała już raz w
 *    AuthService.refreshToken - kontrola była, ale nie na wszystkich ścieżkach.
 *
 *    Sprawdzamy isBlocked(), a NIE isAccountNonLocked() - dokładnie z tego samego powodu,
 *    dla którego robi tak JwtFilter. Ta druga obejmuje też blokadę po nieudanych logowaniach,
 *    którą KAŻDY może wywołać na KAŻDYM, wpisując kilka razy złe hasło na znany adres.
 *    Użyta tutaj dałaby gotowe narzędzie do odcinania dowolnego użytkownika od logowania
 *    Google, bez znajomości jego hasła.
 */
@Slf4j
@Service
public class GoogleOidcUserService extends OidcUserService {

    /**
     * Długość losowego sekretu, z którego liczony jest hash hasła konta założonego przez
     * Google. Sekret jest natychmiast porzucany - nikt go nigdy nie pozna, także my.
     * Dlaczego w ogóle hash, zamiast NULL-a w kolumnie: changeset 0014-users-google-account.xml.
     */
    private static final int PLACEHOLDER_SECRET_BYTES = 32;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserService userService;
    private final PasswordEncoder passwordEncoder;

    public GoogleOidcUserService(UserService userService, PasswordEncoder passwordEncoder) {
        this.userService = userService;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public OidcUser loadUser(OidcUserRequest userRequest) throws OAuth2AuthenticationException {
        // super.loadUser rozmawia z Google (token ID + końcówka userinfo). Cała NASZA
        // logika siedzi w resolve() poniżej - rozdzielone właśnie po to, żeby dało się ją
        // wykonać w teście bez sieci i bez atrapy serwera autoryzacyjnego. Test atrapy
        // sprawdzałby atrapę; tak sprawdza dokładnie te rozgałęzienia, które są nasze.
        OidcUser oidcUser = super.loadUser(userRequest);
        resolve(oidcUser);
        return oidcUser;
    }

    /**
     * Sprowadza tożsamość z Google do konta w tej aplikacji - obie kontrole i powiązanie.
     *
     * Wydzielone z loadUser wyłącznie dla testowalności (patrz wyżej); poza testami ma
     * dokładnie jednego wołającego.
     */
    User resolve(OidcUser oidcUser) {
        if (!Boolean.TRUE.equals(oidcUser.getEmailVerified())) {
            // Bez adresu w logu - obowiązuje tu ta sama reguła co wszędzie indziej.
            log.warn("Odrzucono logowanie Google: adres nie jest potwierdzony przez dostawcę");
            throw reject(GoogleAuthError.EMAIL_NOT_VERIFIED,
                    "Konto Google nie ma potwierdzonego adresu email");
        }

        /*
         * Normalizacja adresu JAWNIE, bo nie przychodzi on tu przez DTO - kompaktowy
         * konstruktor, który normalizuje LoginRequest czy UserRequestDTO, nie ma na tej
         * ścieżce czego tknąć. To trzecie takie miejsce, obok UserDetailsServiceImpl
         * i AdminBootstrap. Bez tego PostgreSQL (porównujący teksty z rozróżnianiem
         * wielkości liter) potraktowałby "Jan@Example.com" z Google jako inny adres niż
         * "jan@example.com" w bazie i założyłby DRUGIE konto na ten sam adres - czyli
         * dziurę w modelu tożsamości, a nie niedogodność.
         */
        String email = EmailNormalizer.normalize(oidcUser.getEmail());

        User user = userService.findOrCreateGoogleUser(
                oidcUser.getSubject(), email, oidcUser.getFullName(), placeholderPasswordHash());

        if (user.isBlocked()) {
            log.warn("Odrzucono logowanie Google konta zablokowanego, id={}", user.getId());
            throw reject(GoogleAuthError.ACCOUNT_BLOCKED, "Konto zostało zablokowane");
        }

        log.debug("Zalogowano przez Google, id={}", user.getId());
        return user;
    }

    /**
     * Hash losowego sekretu dla konta, które hasła nie ma i mieć nie będzie.
     *
     * Liczony przy KAŻDYM logowaniu, także wtedy, gdy konto już istnieje i wartość zostanie
     * zignorowana. To świadomy koszt jednego BCrypta na logowanie przez Google: wariant
     * "policz tylko, gdy zakładasz konto" wymagałby wcześniejszego sprawdzenia, czy konto
     * istnieje, czyli dodatkowego zapytania na każdej ścieżce - i rozdzielenia decyzji
     * o zakładaniu konta na dwa miejsca, z wyścigiem pomiędzy nimi.
     */
    private String placeholderPasswordHash() {
        byte[] secret = new byte[PLACEHOLDER_SECRET_BYTES];
        RANDOM.nextBytes(secret);
        return passwordEncoder.encode(Base64.getEncoder().encodeToString(secret));
    }

    private OAuth2AuthenticationException reject(String code, String description) {
        // Kod ląduje w OAuth2Error i stamtąd odczytuje go handler porażki, żeby dokleić
        // go do adresu powrotnego. Opis zostaje po stronie serwera.
        return new OAuth2AuthenticationException(new OAuth2Error(code, description, null), description);
    }
}
