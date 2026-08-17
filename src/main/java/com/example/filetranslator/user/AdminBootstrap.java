/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.filetranslator.user;

import com.example.filetranslator.common.validation.EmailNormalizer;
import com.example.filetranslator.user.dto.UserRequestDTO;
import com.example.filetranslator.user.model.Role;
import com.example.filetranslator.user.model.User;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Zakłada przy starcie jedno konto z rolą ADMIN, jeśli go jeszcze nie ma.
 *
 * Po co: Role.ADMIN był w enumie od początku, ale nic go nigdy nie przypisywało -
 * jedyne miejsce zakładania konta (UserService.createConfirmedUser) wpisuje na sztywno
 * Role.USER. Skutek był taki, że /actuator/metrics i /actuator/prometheus stały za
 * hasRole("ADMIN") nieosiągalne dla nikogo, czyli były martwe.
 *
 * To PIERWSZY poza potwierdzeniem rejestracji pisarz do tabeli users, więc obowiązują
 * go te same niezmienniki: konto powstaje od razu z enabled = true (wiersz z enabled =
 * false jest w tej aplikacji stanem niemożliwym), a hasło idzie przez BCrypt.
 *
 * Dlaczego kod, a nie changeset Liquibase: migracja nie policzy BCrypta, więc trzeba by
 * wpisać gotowy hash do repozytorium - czyli rozesłać to samo hasło administratora do
 * wszystkich środowisk. Taki wiersz wchodziłby też do każdego kontekstu testowego
 * i łamałby konwencję, że żaden changeset nie wstawia danych.
 *
 * Dwie reguły poniżej są nośne i nie wolno ich "uprościć":
 * - istniejącego konta NIE nadpisujemy hasłem z konfiguracji (restart nie może cofać
 *   rotacji hasła),
 * - istniejącego konta z rolą USER NIE podnosimy do ADMIN (literówka w adresie nie może
 *   po cichu oddać cudzego konta razem z dostępem do aktuatora).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminBootstrap implements ApplicationRunner {

    /**
     * Imię konta technicznego. Stała, nie ustawienie - nikt tego nigdy nie będzie zmieniał,
     * a każdy klucz konfiguracji trzeba potem opisać i utrzymać.
     */
    private static final String ADMIN_NAME = "Administrator";

    private final AdminProperties properties;
    private final UserService userService;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final Validator validator;

    /**
     * Cienka obwoluta na createAdminIfMissing - tak jak @Scheduled w OutboxPublisher.
     * Cała logika siedzi w metodzie publicznej, którą testy wołają wprost, zamiast
     * polegać na tym, co wykona się przy starcie kontekstu.
     */
    @Override
    public void run(ApplicationArguments args) {
        if (!properties.enabled()) {
            log.debug("Zakładanie konta administratora wyłączone (app.admin.enabled=false)");
            return;
        }
        createAdminIfMissing(properties.email(), ADMIN_NAME, properties.password());
    }

    /**
     * Zakłada konto administratora, jeśli konta o tym adresie jeszcze nie ma.
     *
     * Rzuca IllegalStateException przy danych niespełniających polityki - to celowe
     * zatrzymanie startu aplikacji. Runner wykonuje się po migracjach Liquibase i przed
     * ApplicationReadyEvent, a wyjątek z niego zamyka kontekst i kończy proces kodem
     * niezerowym, więc błędna konfiguracja jest widoczna od razu, a nie przy pierwszej
     * próbie zalogowania.
     */
    public void createAdminIfMissing(String rawEmail, String name, String rawPassword) {
        // Adres z konfiguracji nie przechodzi przez żadne DTO wejściowe, więc postać
        // kanoniczną trzeba wymusić tutaj. Bez tego ADMIN_EMAIL zapisany wielkimi
        // literami zakładałby konto, do którego nie da się zalogować adresem małymi.
        String email = EmailNormalizer.normalize(rawEmail);

        validate(name, email, rawPassword);

        Optional<User> istniejacy = userRepository.findByEmail(email);
        if (istniejacy.isPresent()) {
            User user = istniejacy.get();
            if (user.getRole() == Role.ADMIN) {
                log.info("Konto administratora już istnieje (id={}) - nie zmieniam hasła", user.getId());
            } else {
                // WARN, nie INFO: to jedyny sygnał, jaki dostaje operator, gdy pomylił adres
                // i wskazał konto prawdziwego użytkownika. Komunikat musi mówić, czego NIE zrobił.
                log.warn("Konto o podanym adresie istnieje z rolą {} (id={}) - NIE podnoszę uprawnień "
                        + "do ADMIN. Popraw app.admin.email albo zmień rolę ręcznie.",
                        user.getRole(), user.getId());
            }
            return;
        }

        try {
            User admin = userService.createAdmin(email, name, passwordEncoder.encode(rawPassword));
            log.info("Utworzono konto administratora (id={})", admin.getId());
        } catch (DataIntegrityViolationException e) {
            /*
             * Wyścig dwóch instancji startujących równocześnie - rozstrzyga go unikat
             * uk_users_email. Traktujemy jak "już istnieje" i NIE przerywamy startu:
             * odmowa wstania z powodu tego, że sąsiednia instancja zdążyła pierwsza,
             * byłaby gorszą awarią niż problem, przed którym chroni.
             *
             * Wyjątek musi być łapany TUTAJ, poza granicą @Transactional z UserService -
             * w środku transakcja jest już oznaczona rollback-only i przy zatwierdzaniu
             * poleciałby UnexpectedRollbackException obok tego handlera.
             *
             * Świadomie bez e.getMessage() w logu: tekst sterownika zawiera kolidującą
             * wartość, czyli adres email.
             */
            log.info("Konto administratora zostało w międzyczasie założone przez inną instancję");
        }
    }

    /**
     * Sprawdza dane konta polityką rejestracji - przez UserRequestDTO, nie przez własny
     * komplet adnotacji.
     *
     * Dzięki temu konto administratora jest trzymane DOKŁADNIE tym samym kontraktem co
     * konto zwykłego użytkownika (@ValidPassword, @ValidEmail, długość imienia) i nie da
     * się przez konfigurację wprowadzić hasła słabszego, niż przyjmuje rejestracja.
     * Osobny rekord walidacyjny byłby drugą kopią tych samych reguł, czyli dokładnie tym
     * rozjazdem, przed którym @ValidPassword ma chronić.
     */
    private void validate(String name, String email, String rawPassword) {
        Set<ConstraintViolation<UserRequestDTO>> naruszenia =
                validator.validate(new UserRequestDTO(name, email, rawPassword));

        if (naruszenia.isEmpty()) {
            return;
        }

        /*
         * Komunikat składany WYŁĄCZNIE ze ścieżki pola i tekstu reguły. ConstraintViolation
         * niesie też getInvalidValue(), a tam siedzi raz jawne hasło, raz adres email -
         * jedno i drugie ma zakaz trafiania do logów, a ten wyjątek kończy start aplikacji,
         * więc jego treść pojawi się w konsoli i w zbieraczu logów.
         */
        String opis = naruszenia.stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                .sorted()
                .collect(Collectors.joining("; "));

        throw new IllegalStateException(
                "Niepoprawna konfiguracja konta administratora (app.admin.*): " + opis);
    }
}
