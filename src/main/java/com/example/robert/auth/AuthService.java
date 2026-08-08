/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.robert.auth;

import com.example.robert.auth.dto.TokenPair;
import com.example.robert.auth.model.PasswordResetToken;
import com.example.robert.auth.model.PendingRegistration;
import com.example.robert.auth.repository.PasswordResetTokenRepository;
import com.example.robert.auth.repository.PendingRegistrationRepository;
import com.example.robert.common.exception.InvalidTokenException;
import com.example.robert.common.exception.JwtAuthenticationException;
import com.example.robert.common.exception.TokenExpiredException;
import com.example.robert.common.security.JwtUtil;
import com.example.robert.common.security.TokenHasher;
import com.example.robert.notification.MailOutbox;
import com.example.robert.user.UserService;
import com.example.robert.user.dto.UserRequestDTO;
import com.example.robert.user.model.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Service do obsługi autentykacji - login, rejestracja, reset hasła.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private static final long REGISTRATION_VALIDITY_HOURS = 24;

    /**
     * Link do resetu hasła żyje krótko - godzinę, nie dobę jak link rejestracyjny.
     * Różnica jest celowa: token resetu jest pełnym kluczem do istniejącego konta,
     * a token rejestracyjny tylko aktywuje konto, którego jeszcze nie ma.
     */
    private static final long PASSWORD_RESET_VALIDITY_HOURS = 1;

    private final AuthenticationManager authenticationManager;
    private final JwtUtil jwtUtil;
    private final UserService userService;
    private final PasswordEncoder passwordEncoder;
    private final PendingRegistrationRepository pendingRegistrationRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final RefreshTokenService refreshTokenService;
    private final MailOutbox mailOutbox;

    /**
     * Celowo BEZ @Transactional.
     *
     * Transakcja obejmowałaby porównanie hasha BCrypt, a to z definicji operacja
     * kosztowna (kilkadziesiąt-kilkaset ms). Przez ten czas połączenie z puli byłoby
     * zajęte, choć nic z bazy nie jest w tym momencie potrzebne - przy serii logowań
     * pula wysycha na czekaniu na procesor, a nie na bazę.
     *
     * Nie ma tu też czego rollbackować: jedyny zapis to rejestracja sesji, a
     * RefreshTokenService.startSession ma własną transakcję. Licznik nieudanych logowań
     * jest po stronie LoginAttemptService i tak czy tak działa w REQUIRES_NEW,
     * bo musi przeżyć wyjątek, który go wywołał.
     */
    public TokenPair login(String email, String password) {
        // Rzuca BadCredentialsException / DisabledException / LockedException -
        // wszystkie mapowane centralnie w GlobalExceptionHandler.
        Authentication auth = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(email, password)
        );

        String accessToken = jwtUtil.generateToken(auth.getName());
        String refreshToken = jwtUtil.generateRefreshToken(auth.getName());

        // Token odświeżający jest rejestrowany w bazie - dopiero to pozwala go później
        // unieważnić (wylogowanie, wykrycie kradzieży). Sam JWT jest nieodwoływalny.
        refreshTokenService.startSession(auth.getName(), refreshToken);

        // Bez adresu email w logu - to dane osobowe, a logi trafiają do systemów,
        // które mają zwykle szerszy dostęp niż sama baza. Do powiązania wpisu
        // z konkretnym żądaniem służy traceId dokładany przez TraceIdFilter.
        log.info("Poprawne logowanie użytkownika");
        return new TokenPair(accessToken, refreshToken);
    }

    /**
     * Przyjmuje zgłoszenie rejestracji. Konto powstaje dopiero przy potwierdzeniu adresu.
     *
     * Metoda nigdy nie sygnalizuje, czy adres jest już zarejestrowany - odpowiedź jest
     * identyczna w każdym przypadku. Poprzednia wersja rzucała tu 409, co zamieniało
     * endpoint w narzędzie do sprawdzania, kto ma u nas konto.
     */
    @Transactional
    public void register(UserRequestDTO dto) {
        log.info("Przyjęto zgłoszenie rejestracji");

        if (userService.existsByEmail(dto.email())) {
            /*
             * Konto już istnieje, więc nie zakładamy zgłoszenia. Nie odpowiadamy też
             * błędem - informację dostaje właściciel skrzynki, a nie ten, kto wysłał
             * żądanie. Jeśli to pomyłka prawowitego użytkownika, mail podpowie mu
             * zalogowanie albo reset hasła; jeśli ktoś sonduje adresy, nie dowie się nic.
             */
            log.info("Zgłoszenie na adres z istniejącym kontem - wysyłamy powiadomienie zamiast zakładać wpis");
            mailOutbox.enqueueAccountExists(dto.email());
            return;
        }

        /*
         * Każde zgłoszenie to NOWY wiersz - nie nadpisujemy istniejących zgłoszeń na ten
         * sam adres. To jest cała istota tego modelu: gdyby zgłoszenia się nadpisywały,
         * obca osoba mogłaby podmienić hash hasła w trwającej rejestracji, a ofiara,
         * klikając najnowszy link ze swojej skrzynki, aktywowałaby konto z cudzym hasłem.
         * Tutaj potwierdzenie aktywuje dokładnie to zgłoszenie, którego token przyszedł
         * w klikniętym linku, więc obce zgłoszenia leżą obok i wygasają nieużyte.
         */
        String rawToken = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();

        pendingRegistrationRepository.save(new PendingRegistration(
                dto.email(),
                dto.name(),
                passwordEncoder.encode(dto.password()),
                TokenHasher.sha256Hex(rawToken),
                now,
                now.plusHours(REGISTRATION_VALIDITY_HOURS)
        ));

        // Zamówienie maila trafia do skrzynki nadawczej w TEJ SAMEJ transakcji co zgłoszenie.
        // Gdyby coś wywaliło wyjątek wyżej, wycofa się jedno i drugie: nie wyślemy linku
        // do rejestracji, która nie powstała. A gdy transakcja przejdzie, zamówienie jest
        // już trwałe - awaria SMTP albo restart poda nie kasują go, tylko odkładają.
        mailOutbox.enqueueVerification(dto.email(), dto.name(), rawToken);
    }

    /**
     * Potwierdza adres i zakłada konto z danych zgłoszenia.
     */
    @Transactional
    public void confirmEmail(String rawToken) {
        /*
         * Nieznany token to DWA nieodróżnialne przypadki: token zmyślony albo token, który
         * chwilę temu zadziałał. Potwierdzenie kasuje wszystkie zgłoszenia na dany adres
         * (patrz niżej), więc udany link jest po użyciu dokładnie tak samo nieznany jak
         * podrobiony. Najczęstszym wyzwalaczem jest zwykłe odświeżenie strony potwierdzenia,
         * czyli sytuacja, w której wszystko poszło DOBRZE - dlatego komunikat wymienia obie
         * możliwości, zamiast wybierać tę gorszą i straszyć użytkownika awarią. Reset hasła
         * ma ten sam komunikat, ale tam stany da się odróżnić, bo used_at zostaje w tabeli
         * do wygaśnięcia.
         *
         * Czego serwer powiedzieć NIE MOŻE: "konto jest aktywne, zaloguj się". Dla linku
         * wygasłego albo zmyślonego byłoby to nieprawdą i odesłałoby użytkownika do konta,
         * które nie istnieje. Ten komunikat należy do frontu - on jeden wie, że jego własne
         * potwierdzenie właśnie się udało, i dlatego po sukcesie zdejmuje token z adresu.
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
             * Konto powstało już z innego zgłoszenia na ten adres (np. użytkownik
             * rejestrował się dwa razy i kliknął oba linki). Nie ruszamy hasła istniejącego
             * konta - nadpisanie go danymi ze starszego zgłoszenia byłoby cichą zmianą
             * hasła. Kasujemy tylko zbędne zgłoszenie.
             */
            pendingRegistrationRepository.deleteAllByEmail(pending.getEmail());
            log.info("Potwierdzenie dla adresu, na którym konto już istnieje - zgłoszenie usunięte");
            return;
        }

        // Hasło jest już zahashowane w poczekalni - przenosimy hash, nie kodujemy po raz drugi.
        User created = userService.createConfirmedUser(
                pending.getEmail(), pending.getName(), pending.getPasswordHash());

        // Kasujemy WSZYSTKIE zgłoszenia na ten adres, nie tylko wykorzystane. Pozostałe
        // (także obce) straciły sens, bo adres jest od teraz zajęty przez istniejące konto.
        pendingRegistrationRepository.deleteAllByEmail(pending.getEmail());

        log.info("Email potwierdzony, konto utworzone (id={})", created.getId());
    }

    /**
     * Rozpoczyna reset hasła.
     *
     * Tak jak rejestracja: odpowiedź jest identyczna dla adresu znanego i nieznanego.
     * Endpoint resetu hasła jest klasycznym miejscem wycieku listy użytkowników.
     */
    @Transactional
    public void requestPasswordReset(String email) {
        Optional<User> found = userService.findEntityByEmail(email);

        if (found.isEmpty()) {
            log.info("Żądanie resetu hasła dla nieznanego adresu - pomijamy");
            return;
        }

        User user = found.get();
        LocalDateTime now = LocalDateTime.now();

        // W obiegu ma być najwyżej jeden żywy link. Bez tego seria żądań zostawia stos
        // ważnych tokenów, a każdy z nich jest pełnym kluczem do konta.
        passwordResetTokenRepository.invalidateAllForUser(user.getId(), now);

        String rawToken = UUID.randomUUID().toString();
        passwordResetTokenRepository.save(new PasswordResetToken(
                TokenHasher.sha256Hex(rawToken),
                user,
                now,
                now.plusHours(PASSWORD_RESET_VALIDITY_HOURS)
        ));

        mailOutbox.enqueuePasswordReset(user.getEmail(), user.getName(), rawToken);

        log.info("Wysłano link do resetu hasła (id={})", user.getId());
    }

    /**
     * Ustawia nowe hasło na podstawie jednorazowego tokenu.
     */
    @Transactional
    public void resetPassword(String rawToken, String newPassword) {
        PasswordResetToken token = passwordResetTokenRepository
                .findByTokenHash(TokenHasher.sha256Hex(rawToken))
                .orElseThrow(() -> new InvalidTokenException("Nieprawidłowy link do resetu hasła"));

        if (token.isUsed()) {
            // Osobny komunikat od "nieprawidłowy": użytkownik wie, że reset się udał,
            // i nie próbuje w panice kolejnych rzeczy.
            throw new InvalidTokenException("Ten link został już wykorzystany");
        }

        if (token.isExpired()) {
            throw new TokenExpiredException("Link wygasł - poproś o nowy");
        }

        User user = token.getUser();
        userService.updatePassword(user, passwordEncoder.encode(newPassword));
        token.setUsedAt(LocalDateTime.now()); // encja zarządzana - dirty checking zapisze zmianę

        /*
         * Reset hasła MUSI zerwać istniejące sesje. Jeśli powodem resetu było przejęcie
         * konta, to napastnik ma ważny token odświeżający - bez tego kroku zmiana hasła
         * niczego mu nie odbiera i zostaje w koncie na kolejne 7 dni.
         */
        int revoked = refreshTokenService.revokeAllSessions(user.getId());

        // Zerujemy też blokadę: użytkownik, który zapomniał hasła, zwykle najpierw
        // zdążył je pomylić kilka razy i zastałby konto zablokowane zaraz po resecie.
        userService.clearLoginFailures(user.getEmail());

        log.info("Hasło zmienione (id={}), unieważniono {} token(ów) odświeżających",
                user.getId(), revoked);
    }

    @Transactional
    public TokenPair refreshToken(String refreshToken) {
        // 1. Kryptografia: podpis i typ tokenu. Odsiewa tokeny podrobione i access tokeny
        //    podstawione w miejsce odświeżających, zanim w ogóle ruszymy bazę.
        String type = jwtUtil.extractTokenType(refreshToken);
        if (!"refresh".equals(type)) {
            throw new JwtAuthenticationException("Nieprawidłowy typ tokenu", "INVALID_TOKEN_TYPE");
        }

        String username = jwtUtil.extractUsername(refreshToken);
        String accessToken = jwtUtil.generateToken(username);
        String newRefreshToken = jwtUtil.generateRefreshToken(username);

        // 2. Stan po stronie serwera: czy ten token nie został już zużyty albo unieważniony.
        //    Rotacja zużywa stary token i zapisuje nowy w tej samej rodzinie.
        //    Rzuca wyjątek, jeśli token jest nieznany, zużyty (kradzież) lub wygasły -
        //    wygenerowane wyżej tokeny są wtedy po prostu porzucane.
        refreshTokenService.rotate(refreshToken, newRefreshToken);

        return new TokenPair(accessToken, newRefreshToken);
    }

    @Transactional
    public void logout(String refreshToken) {
        if (refreshToken == null) {
            // Wylogowanie bez tokenu nie jest błędem - kontroler i tak wyczyści ciasteczka.
            return;
        }
        refreshTokenService.revokeSession(refreshToken);
    }
}
