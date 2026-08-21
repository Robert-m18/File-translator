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
 * Kontrole bezpieczeństwa znajdują się tutaj, a nie w handlerze sukcesu, ponieważ wyjątek
 * rzucony z tego miejsca jest wyjątkiem uwierzytelnienia i trafia do handlera niepowodzenia,
 * który odsyła użytkownika na front z kodem błędu. Handler sukcesu nie ma jak odmówić: jego
 * sygnatura nie przewiduje takiego wyjątku, a użytkownik jest na tym etapie już uwierzytelniony.
 *
 * Wykonywane są dwie kontrole, których nie realizuje żadna inna warstwa:
 *
 * 1. Potwierdzenie adresu przez dostawcę tożsamości. Bez niego adres z tokenu tożsamości jest
 *    tylko napisem, a ponieważ to właśnie po adresie konto zewnętrzne łączone jest z istniejącym
 *    kontem hasłowym, przepuszczenie niepotwierdzonego adresu zamieniłoby łączenie kont w ich
 *    przejmowanie - wystarczyłoby założyć konto u dostawcy na cudzy adres.
 *
 * 2. Blokada administracyjna. Logowanie przez OAuth2 omija w całości mechanizm sprawdzający stan
 *    konta przy logowaniu hasłem, więc bez tej kontroli blokada nie działałaby na tej ścieżce:
 *    zablokowany użytkownik dostawałby odmowę przy logowaniu hasłem i wchodził bokiem przez
 *    dostawcę zewnętrznego.
 *
 *    Sprawdzana jest wyłącznie blokada administracyjna, a nie ogólny stan konta - z tego samego
 *    powodu co w filtrze uwierzytelniającym. Stan ogólny obejmuje także blokadę po nieudanych
 *    logowaniach, którą każdy może wywołać na dowolnym koncie, podając kilka razy złe hasło do
 *    znanego adresu. Użycie go tutaj dałoby gotowe narzędzie do odcinania dowolnego użytkownika
 *    od logowania zewnętrznego, bez znajomości jego hasła.
 */
@Slf4j
@Service
public class GoogleOidcUserService extends OidcUserService {

    /**
     * Długość losowego sekretu, z którego liczony jest hash hasła konta założonego przez
     * dostawcę zewnętrznego. Sekret jest natychmiast porzucany i nigdzie nie zapisywany.
     *
     * Hash zamiast wartości pustej, ponieważ kolumna hasła jest wymagana, a mechanizm logowania
     * hasłem porównuje podaną wartość z jej zawartością. Dzięki temu próba logowania hasłem na
     * takie konto kończy się zwykłym błędem poświadczeń, bez ujawniania, że konto powstało
     * u dostawcy zewnętrznego.
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
        // Wywołanie nadrzędne rozmawia z dostawcą tożsamości. Logika tej aplikacji siedzi
        // w metodzie poniżej - rozdzielenie pozwala wykonać ją w teście bez sieci i bez atrapy
        // serwera autoryzacyjnego, dzięki czemu test sprawdza rzeczywiste rozgałęzienia
        // aplikacji, a nie zachowanie atrapy.
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
         * Adres normalizowany jest jawnie, ponieważ nie przychodzi tu przez DTO, a to konstruktory
         * DTO wykonują normalizację na pozostałych ścieżkach. Bez niej baza porównująca teksty
         * z rozróżnianiem wielkości liter potraktowałaby adres zapisany wielkimi literami jako
         * inny niż ten sam adres w bazie i założyłaby drugie konto na ten sam adres - czyli lukę
         * w modelu tożsamości, a nie zwykłą niedogodność.
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
     * Wartość liczona jest przy każdym logowaniu, także wtedy, gdy konto już istnieje i zostanie
     * zignorowana. Jest to świadomy koszt jednego przeliczenia hasła na logowanie: liczenie go
     * wyłącznie przy zakładaniu konta wymagałoby wcześniejszego sprawdzenia, czy konto istnieje,
     * czyli dodatkowego zapytania na każdej ścieżce, oraz rozdzielenia decyzji o zakładaniu konta
     * na dwa miejsca, z wyścigiem pomiędzy nimi.
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
