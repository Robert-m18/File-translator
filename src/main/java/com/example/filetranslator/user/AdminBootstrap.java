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
 * Jest to jedyna ścieżka nadająca rolę administratora: rejestracja przypisuje rolę zwykłego
 * użytkownika na sztywno, więc bez tego komponentu endpointy chronione rolą administratora
 * byłyby nieosiągalne dla nikogo.
 *
 * Klasa jest drugim, obok potwierdzenia rejestracji, miejscem zapisującym do tabeli kont, więc
 * obowiązują ją te same niezmienniki: konto powstaje od razu aktywne, a hasło przechodzi przez
 * funkcję haszującą.
 *
 * Konto zakładane jest w kodzie, a nie migracją bazy, ponieważ migracja nie policzy skrótu
 * hasła - trzeba by wpisać do repozytorium gotowy hash, czyli rozesłać to samo hasło
 * administratora do wszystkich środowisk. Taki wiersz trafiałby też do każdego kontekstu
 * testowego i łamał zasadę, że migracje nie wstawiają danych.
 *
 * Dwie reguły poniżej są nośne i nie wolno ich upraszczać: hasło istniejącego konta nie jest
 * nadpisywane wartością z konfiguracji, żeby restart nie cofał rotacji hasła, a istniejące konto
 * zwykłego użytkownika nie jest podnoszone do roli administratora, żeby literówka w adresie nie
 * oddała po cichu cudzego konta razem z dostępem do danych operacyjnych.
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
     * Cienka obwoluta na metodę poniżej. Cała logika znajduje się w metodzie publicznej,
     * którą testy wołają wprost, zamiast polegać na tym, co wykona się przy starcie
     * kontekstu aplikacji.
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
                // Poziom WARN, nie INFO: jest to jedyny sygnał dla operatora, który pomylił adres
                // i wskazał konto istniejącego użytkownika. Komunikat mówi wprost, czego nie zrobiono.
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
             * Wyścig dwóch instancji startujących równocześnie rozstrzyga
             * unikalny indeks na adresie. Sytuacja traktowana jest jak "konto już istnieje" i nie
             * przerywa startu: odmowa wstania z powodu tego, że sąsiednia instancja zdążyła
             * pierwsza, byłaby gorszą awarią niż problem, przed którym ta kontrola chroni.
             *
             * Wyjątek musi być łapany w tym miejscu, poza granicą transakcji serwisu:
             * w środku transakcja jest już oznaczona rollback-only i przy zatwierdzaniu
             * poleciałby UnexpectedRollbackException obok tego handlera.
             *
             * W logu świadomie nie ma komunikatu wyjątku: tekst sterownika zawiera kolidującą
             * wartość, czyli adres email.
             */
            log.info("Konto administratora zostało w międzyczasie założone przez inną instancję");
        }
    }

    /**
     * Sprawdza dane konta polityką rejestracji - przez UserRequestDTO, nie przez własny
     * komplet adnotacji.
     *
     * Dzięki temu konto administratora podlega dokładnie temu samemu kontraktowi co
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
         * Komunikat składany jest wyłącznie ze ścieżki pola i tekstu reguły. Naruszenie reguły
         * niesie także odrzuconą wartość, czyli raz jawne hasło, raz adres e-mail -
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
