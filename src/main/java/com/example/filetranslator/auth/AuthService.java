/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.filetranslator.auth;

import com.example.filetranslator.auth.dto.TokenPair;
import com.example.filetranslator.auth.model.PasswordResetToken;
import com.example.filetranslator.auth.model.PendingRegistration;
import com.example.filetranslator.auth.repository.PasswordResetTokenRepository;
import com.example.filetranslator.auth.repository.PendingRegistrationRepository;
import com.example.filetranslator.common.exception.AccountBlockedException;
import com.example.filetranslator.common.exception.InvalidTokenException;
import com.example.filetranslator.common.exception.JwtAuthenticationException;
import com.example.filetranslator.common.exception.TokenExpiredException;
import com.example.filetranslator.common.security.JwtUtil;
import com.example.filetranslator.common.security.TokenHasher;
import com.example.filetranslator.notification.MailOutbox;
import com.example.filetranslator.user.UserService;
import com.example.filetranslator.user.dto.UserRequestDTO;
import com.example.filetranslator.user.model.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Uwierzytelnianie i cykl życia konta: logowanie, rejestracja z potwierdzeniem adresu, reset
 * hasła, odświeżanie i kończenie sesji.
 *
 * Dwie zasady obowiązują w całej klasie. Po pierwsze, żadna odpowiedź nie zdradza, czy dany
 * adres ma konto - rejestracja i reset hasła odpowiadają identycznie w obu przypadkach, bo są
 * to klasyczne miejsca wycieku listy użytkowników. Po drugie, zamówienia maili trafiają do
 * skrzynki nadawczej w tej samej transakcji co operacja, którą opisują, dzięki czemu nie da się
 * wysłać wiadomości o zdarzeniu, które zostało wycofane.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private static final Duration REGISTRATION_VALIDITY = Duration.ofHours(24);

    /**
     * Link do resetu hasła żyje godzinę, a nie dobę jak link rejestracyjny. Różnica jest celowa:
     * token resetu jest pełnym kluczem do istniejącego konta, a token rejestracyjny aktywuje
     * konto, którego jeszcze nie ma.
     */
    private static final Duration PASSWORD_RESET_VALIDITY = Duration.ofHours(1);

    private final AuthenticationManager authenticationManager;
    private final JwtUtil jwtUtil;
    private final UserService userService;
    private final PasswordEncoder passwordEncoder;
    private final PendingRegistrationRepository pendingRegistrationRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final RefreshTokenService refreshTokenService;
    private final MailOutbox mailOutbox;

    /**
     * Weryfikuje poświadczenia i wystawia parę tokenów, rejestrując przy tym sesję.
     *
     * Metoda celowo nie jest transakcyjna. Transakcja obejmowałaby porównanie hasha BCrypt,
     * czyli operację z założenia kosztowną obliczeniowo, i przez cały ten czas zajmowałaby
     * połączenie z puli, mimo że nic z bazy nie jest wtedy potrzebne - przy serii logowań pula
     * wysychałaby na oczekiwaniu na procesor.
     *
     * Nie ma tu też czego wycofywać: jedynym zapisem jest rejestracja sesji, która ma własną
     * transakcję, a licznik nieudanych logowań działa w osobnej transakcji, bo musi przetrwać
     * wyjątek, który go wywołał.
     */
    public TokenPair login(String email, String password) {
        // Rzuca wyjątki opisujące złe poświadczenia, konto niepotwierdzone i zablokowane -
        // wszystkie mapowane centralnie na odpowiedzi HTTP.
        Authentication auth = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(email, password)
        );

        String accessToken = jwtUtil.generateToken(auth.getName());
        String refreshToken = jwtUtil.generateRefreshToken(auth.getName());

        // Rejestracja tokenu odświeżającego w bazie jest tym, co pozwala go później unieważnić
        // przy wylogowaniu lub wykryciu kradzieży. Sam token JWT jest nieodwoływalny.
        refreshTokenService.startSession(auth.getName(), refreshToken);

        // Bez adresu w logu - to dana osobowa, a logi trafiają do systemów o szerszym dostępie
        // niż baza. Powiązanie wpisu z żądaniem zapewnia identyfikator żądania.
        log.info("Poprawne logowanie użytkownika");
        return new TokenPair(accessToken, refreshToken);
    }

    /**
     * Przyjmuje zgłoszenie rejestracji. Konto powstaje dopiero przy potwierdzeniu adresu.
     *
     * Metoda nigdy nie sygnalizuje, czy adres jest już zarejestrowany - odpowiedź jest
     * identyczna w każdym przypadku, więc endpointu nie da się użyć do sprawdzania, kto ma konto.
     */
    @Transactional
    public void register(UserRequestDTO dto) {
        log.info("Przyjęto zgłoszenie rejestracji");

        if (userService.existsByEmail(dto.email())) {
            /*
             * Konto już istnieje, więc zgłoszenie nie powstaje. Informację dostaje właściciel
             * skrzynki, a nie autor żądania: jeśli jest to pomyłka prawowitego użytkownika, mail
             * podpowiada zalogowanie albo reset hasła, a jeśli ktoś sonduje adresy, nie dowiaduje
             * się niczego.
             */
            log.info("Zgłoszenie na adres z istniejącym kontem - wysyłamy powiadomienie zamiast zakładać wpis");
            mailOutbox.enqueueAccountExists(dto.email());
            return;
        }

        /*
         * Każde zgłoszenie tworzy nowy wiersz; zgłoszenia na ten sam adres nie są nadpisywane.
         * Na tym polega cały ten model: przy nadpisywaniu obca osoba mogłaby podmienić hash hasła
         * w trwającej rejestracji, a ofiara, klikając najnowszy link ze swojej skrzynki,
         * aktywowałaby konto z cudzym hasłem. Tutaj potwierdzenie aktywuje dokładnie to
         * zgłoszenie, którego token przyszedł w klikniętym linku, więc obce zgłoszenia leżą obok
         * i wygasają nieużyte.
         */
        String rawToken = UUID.randomUUID().toString();
        Instant now = Instant.now();

        pendingRegistrationRepository.save(new PendingRegistration(
                dto.email(),
                dto.name(),
                passwordEncoder.encode(dto.password()),
                TokenHasher.sha256Hex(rawToken),
                now,
                now.plus(REGISTRATION_VALIDITY)
        ));

        // Zamówienie maila trafia do skrzynki nadawczej w tej samej transakcji co zgłoszenie.
        // Wyjątek wyżej wycofuje jedno i drugie, więc link do rejestracji, która nie powstała,
        // nie zostanie wysłany. Po zatwierdzeniu zamówienie jest trwałe - awaria serwera poczty
        // ani restart aplikacji go nie kasują, tylko odkładają wysyłkę.
        mailOutbox.enqueueVerification(dto.email(), dto.name(), rawToken);
    }

    /**
     * Potwierdza adres i zakłada konto na podstawie danych ze zgłoszenia.
     */
    @Transactional
    public void confirmEmail(String rawToken) {
        /*
         * Nieznany token oznacza dwa nierozróżnialne przypadki: token zmyślony albo token, który
         * przed chwilą zadziałał. Potwierdzenie kasuje wszystkie zgłoszenia na dany adres, więc
         * użyty link jest potem tak samo nieznany jak podrobiony. Najczęstszym wyzwalaczem jest
         * odświeżenie strony potwierdzenia, czyli sytuacja, w której wszystko poszło dobrze -
         * dlatego komunikat wymienia obie możliwości zamiast straszyć użytkownika awarią.
         *
         * Serwer nie może w tym miejscu odpowiedzieć "konto jest aktywne, zaloguj się": dla linku
         * wygasłego albo zmyślonego byłoby to nieprawdą i odesłałoby użytkownika do nieistniejącego
         * konta. Taki komunikat należy do frontendu, który jako jedyny wie, że jego własne
         * potwierdzenie właśnie się powiodło.
         */
        PendingRegistration pending = pendingRegistrationRepository
                .findByTokenHash(TokenHasher.sha256Hex(rawToken))
                .orElseThrow(() -> new InvalidTokenException(
                        "Link jest nieprawidłowy albo został już wykorzystany"
                                + " - jeśli potwierdzałeś to konto wcześniej, spróbuj się zalogować"));

        if (pending.isExpired()) {
            throw new TokenExpiredException("Token wygasł - zarejestruj się ponownie");
        }

        if (userService.existsByEmail(pending.getEmail())) {
            /*
             * Konto powstało już z innego zgłoszenia na ten adres - na przykład użytkownik
             * rejestrował się dwukrotnie i kliknął oba linki. Hasło istniejącego konta pozostaje
             * nietknięte, bo nadpisanie go danymi ze starszego zgłoszenia byłoby cichą zmianą
             * hasła; kasowane jest wyłącznie zbędne zgłoszenie.
             */
            pendingRegistrationRepository.deleteAllByEmail(pending.getEmail());
            log.info("Potwierdzenie dla adresu, na którym konto już istnieje - zgłoszenie usunięte");
            return;
        }

        // Hasło jest już zahashowane w poczekalni - hash zostaje przeniesiony, a nie zakodowany
        // po raz drugi, bo dałoby to hash hasha i uniemożliwiło logowanie.
        User created = userService.createConfirmedUser(
                pending.getEmail(), pending.getName(), pending.getPasswordHash());

        // Kasowane są wszystkie zgłoszenia na ten adres, nie tylko wykorzystane: pozostałe,
        // również obce, straciły sens, bo adres jest od tej chwili zajęty przez istniejące konto.
        pendingRegistrationRepository.deleteAllByEmail(pending.getEmail());

        log.info("Email potwierdzony, konto utworzone (id={})", created.getId());
    }

    /**
     * Rozpoczyna reset hasła: unieważnia poprzednie linki i wysyła nowy.
     *
     * Podobnie jak przy rejestracji, odpowiedź jest identyczna dla adresu znanego i nieznanego.
     */
    @Transactional
    public void requestPasswordReset(String email) {
        Optional<User> found = userService.findEntityByEmail(email);

        if (found.isEmpty()) {
            log.info("Żądanie resetu hasła dla nieznanego adresu - pomijamy");
            return;
        }

        User user = found.get();
        Instant now = Instant.now();

        // W obiegu ma pozostawać najwyżej jeden ważny link. Bez unieważnienia poprzednich seria
        // żądań zostawiałaby stos ważnych tokenów, z których każdy jest pełnym kluczem do konta.
        passwordResetTokenRepository.invalidateAllForUser(user.getId(), now);

        String rawToken = UUID.randomUUID().toString();
        passwordResetTokenRepository.save(new PasswordResetToken(
                TokenHasher.sha256Hex(rawToken),
                user,
                now,
                now.plus(PASSWORD_RESET_VALIDITY)
        ));

        mailOutbox.enqueuePasswordReset(user.getEmail(), user.getName(), rawToken);

        log.info("Wysłano link do resetu hasła (id={})", user.getId());
    }

    /**
     * Ustawia nowe hasło na podstawie jednorazowego tokenu.
     *
     * Poza zmianą hasła metoda robi dwie rzeczy konieczne dla bezpieczeństwa konta: unieważnia
     * wszystkie sesje i zeruje licznik nieudanych logowań.
     */
    @Transactional
    public void resetPassword(String rawToken, String newPassword) {
        PasswordResetToken token = passwordResetTokenRepository
                .findByTokenHash(TokenHasher.sha256Hex(rawToken))
                .orElseThrow(() -> new InvalidTokenException("Nieprawidłowy link do resetu hasła"));

        if (token.isUsed()) {
            // Komunikat odrębny od "nieprawidłowy link": użytkownik wie, że reset się powiódł,
            // i nie próbuje w panice kolejnych rzeczy. Rozróżnienie jest możliwe, bo data użycia
            // zostaje w tabeli aż do wygaśnięcia tokenu.
            throw new InvalidTokenException("Ten link został już wykorzystany");
        }

        if (token.isExpired()) {
            throw new TokenExpiredException("Link wygasł - poproś o nowy");
        }

        User user = token.getUser();
        userService.updatePassword(user, passwordEncoder.encode(newPassword));
        token.setUsedAt(Instant.now()); // encja zarządzana - zmiana zapisze się przy commicie

        /*
         * Reset hasła musi unieważnić istniejące sesje. Jeżeli jego powodem było przejęcie konta,
         * napastnik dysponuje ważnym tokenem odświeżającym - bez tego kroku zmiana hasła niczego
         * mu nie odbiera i pozostaje w koncie przez cały tydzień ważności tokenu.
         */
        int revoked = refreshTokenService.revokeAllSessions(user.getId());

        // Licznik nieudanych logowań również zostaje wyzerowany: użytkownik, który zapomniał
        // hasła, zwykle najpierw pomylił je kilka razy i zastałby konto zablokowane zaraz po
        // udanym resecie.
        userService.clearLoginFailures(user.getEmail());

        log.info("Hasło zmienione (id={}), unieważniono {} token(ów) odświeżających",
                user.getId(), revoked);
    }

    /**
     * Wymienia token odświeżający na nową parę tokenów, obracając przy tym sesję.
     */
    @Transactional
    public TokenPair refreshToken(String refreshToken) {
        // Najpierw kryptografia: podpis i typ tokenu. Odsiewa tokeny podrobione oraz tokeny
        // dostępowe podstawione w miejsce odświeżających, zanim wykonane zostanie jakiekolwiek
        // zapytanie do bazy.
        String type = jwtUtil.extractTokenType(refreshToken);
        if (!"refresh".equals(type)) {
            throw new JwtAuthenticationException("Nieprawidłowy typ tokenu", "INVALID_TOKEN_TYPE");
        }

        String username = jwtUtil.extractUsername(refreshToken);

        /*
         * Stan konta sprawdzany przed obrotem sesji.
         *
         * Bez tej kontroli blokada administracyjna byłaby nieskuteczna przez cały tydzień
         * ważności tokenu odświeżającego: zablokowany użytkownik nie mógłby się zalogować ani
         * użyć żywego tokenu dostępowego, ale co kwadrans wymieniałby token na nowy i pracował
         * dalej. Usunięcie tokenów przy blokowaniu konta jest drugą, niezależną warstwą - tamta
         * kasuje sesje, ta sprawdza stan konta i żadna nie zastępuje drugiej.
         *
         * Kolejność wobec obrotu sesji jest istotna: po nim przedstawiony token jest już zużyty,
         * więc kontrola wykonana później zwracałaby komunikat o wykryciu kradzieży tokenu zamiast
         * o zablokowanym koncie, a log zapełniałby się fałszywymi ostrzeżeniami o włamaniu przy
         * każdym odświeżeniu z zablokowanego konta.
         */
        userService.findEntityByEmail(username)
                .filter(User::isBlocked)
                .ifPresent(blocked -> {
                    log.warn("Odrzucono odświeżenie sesji zablokowanego konta (id={})", blocked.getId());
                    throw new AccountBlockedException();
                });

        String accessToken = jwtUtil.generateToken(username);
        String newRefreshToken = jwtUtil.generateRefreshToken(username);

        // Na koniec stan po stronie serwera: czy token nie został już zużyty albo unieważniony.
        // Obrót zużywa stary token i zapisuje nowy w tej samej rodzinie, a przy tokenie nieznanym,
        // zużytym (co oznacza kradzież) lub wygasłym rzuca wyjątek - wygenerowane wyżej tokeny
        // zostają wtedy porzucone.
        refreshTokenService.rotate(refreshToken, newRefreshToken);

        return new TokenPair(accessToken, newRefreshToken);
    }

    /** Kończy sesję, unieważniając rodzinę tokenów odświeżających. */
    @Transactional
    public void logout(String refreshToken) {
        if (refreshToken == null) {
            // Wylogowanie bez tokenu nie jest błędem - kontroler i tak wyczyści ciasteczka.
            return;
        }
        refreshTokenService.revokeSession(refreshToken);
    }
}
