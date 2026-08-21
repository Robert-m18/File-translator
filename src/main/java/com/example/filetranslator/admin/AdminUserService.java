/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.filetranslator.admin;

import com.example.filetranslator.admin.exception.AdminActionRejectedException;
import com.example.filetranslator.admin.exception.AdminUserNotFoundException;
import com.example.filetranslator.auth.RefreshTokenService;
import com.example.filetranslator.translation.TranslationService;
import com.example.filetranslator.user.UserService;
import com.example.filetranslator.user.dto.AdminUserView;
import com.example.filetranslator.user.model.Role;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;

/**
 * Operacje panelu administracyjnego na kontach: przegląd, blokada, odblokowanie, zdjęcie
 * blokady logowania, wymuszone wylogowanie i usunięcie konta.
 *
 * Pakiet admin/ jest osobny, ponieważ jego operacje sięgają do dwóch innych obszarów:
 * blokada i wymuszone wylogowanie wymagają unieważnienia sesji z pakietu auth/, a usunięcie
 * konta - skasowania plików z pakietu translation/. Umieszczenie tych metod w pakiecie user/
 * odwróciłoby regułę "auth zależy od user, nigdy odwrotnie" i utworzyło cykl zależności.
 * Pakiet stojący nad pozostałymi zachowuje strzałki acykliczne.
 *
 * Klasa nie sięga do repozytoriów innych pakietów - korzysta wyłącznie z ich serwisów, dzięki
 * czemu zmiana wewnętrznej budowy tamtych pakietów nie przenosi się tutaj.
 *
 * Do logów trafiają identyfikatory konta i administratora, nigdy adres e-mail ani powód
 * blokady. Powód jest treścią podaną przez człowieka o innym człowieku, więc w logu byłby daną
 * osobową bez terminu ważności.
 */
@Slf4j
@Service
public class AdminUserService {

    private final UserService userService;
    private final RefreshTokenService refreshTokenService;

    /** Kasowanie plików usuwanego konta - jedyne wejście tego pakietu do obszaru tłumaczeń. */
    private final TranslationService translationService;

    /**
     * Transakcja wołana programowo, wyłącznie na potrzeby kasowania konta: kasowanie plików musi
     * pozostać poza transakcją (jest wywołaniem po sieci), a decyzja o dopuszczalności kasowania
     * musi znaleźć się w środku, bo opiera się na blokadzie wierszy. Adnotacja na metodzie nie
     * pozwoliłaby rozdzielić jednego od drugiego.
     */
    private final TransactionTemplate transaction;

    public AdminUserService(UserService userService,
                            RefreshTokenService refreshTokenService,
                            TranslationService translationService,
                            PlatformTransactionManager transactionManager) {
        this.userService = userService;
        this.refreshTokenService = refreshTokenService;
        this.translationService = translationService;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    @Transactional(readOnly = true)
    public Page<AdminUserView> list(String query, Pageable pageable) {
        return userService.findAdminViews(query, pageable);
    }

    @Transactional(readOnly = true)
    public AdminUserView get(Long id) {
        return userService.findAdminView(id).orElseThrow(AdminUserNotFoundException::new);
    }

    /**
     * Blokuje konto i natychmiast unieważnia jego sesje.
     *
     * Samo ustawienie daty blokady nie wystarcza: token odświeżający żyje siedem dni, więc bez
     * unieważnienia sesji zablokowany użytkownik odnawiałby sobie dostęp przez tydzień.
     * Sprawdzenie stanu konta przy odświeżaniu sesji stanowi drugą, niezależną warstwę - ta
     * operacja usuwa same tokeny.
     *
     * Żywy token dostępowy przestaje działać przy najbliższym żądaniu dzięki sprawdzeniu
     * w filtrze JWT. Jest to jedyne miejsce, w którym piętnastominutowe okno bezstanowego tokenu
     * zostało domknięte, i było to możliwe tylko dlatego, że filtr i tak czyta wiersz użytkownika
     * przy każdym żądaniu, więc kontrola nie kosztuje dodatkowego zapytania.
     */
    @Transactional
    public AdminUserView block(Long targetId, Long adminId, String reason) {
        AdminUserView target = get(targetId);

        /*
         * Kontrola ostatniego administratora poprzedza kontrolę działania na własnym koncie.
         * W praktyce może zadziałać tylko przy blokowaniu samego siebie, bo wołający jest
         * niezablokowanym administratorem, więc przy cudzym celu niezablokowani są co najmniej
         * dwaj. Kolejność decyduje o komunikacie: informacja o utracie ostatniego administratora
         * nazywa prawdziwy problem, ponieważ nie ma automatycznej drogi powrotnej do tej roli,
         * podczas gdy komunikat o blokowaniu samego siebie brzmiałby jak drobna niewygoda możliwa
         * do obejścia z drugiego konta.
         */
        if (target.role() == Role.ADMIN) {
            List<Long> unblockedAdmins = userService.lockUnblockedAdminIds();
            if (unblockedAdmins.contains(targetId) && unblockedAdmins.size() <= 1) {
                throw new AdminActionRejectedException("LAST_ADMIN_CANNOT_BE_BLOCKED",
                        "To jedyne niezablokowane konto administratora - jego zablokowanie "
                                + "odcięłoby dostęp do panelu wszystkim");
            }
        }

        if (targetId.equals(adminId)) {
            throw new AdminActionRejectedException("CANNOT_BLOCK_SELF",
                    "Nie można zablokować własnego konta");
        }

        boolean changed = userService.blockAccount(targetId, Instant.now(), reason);

        /*
         * Unieważnienie sesji wykonuje się także przy powtórnym zablokowaniu konta już
         * zablokowanego. Wygląda to na zbędną pracę, a jest odpowiedzią na wyścig: sesja mogła
         * powstać między pierwszą blokadą a tym wywołaniem, gdyby pierwsze przerwało się w połowie.
         * Kosztuje jedno zapytanie po indeksie, a alternatywą jest blokada bez skutku.
         */
        int revoked = refreshTokenService.revokeAllSessions(targetId);

        log.info("Zablokowano konto (id={}, przez id={}, nowa blokada={}, "
                        + "unieważniono {} token(ów) odświeżających)",
                targetId, adminId, changed, revoked);

        return get(targetId);
    }

    /** Zdejmuje blokadę administracyjną. Nowe tokeny nie są wystawiane - użytkownik loguje się sam. */
    @Transactional
    public AdminUserView unblock(Long targetId, Long adminId) {
        AdminUserView target = get(targetId);
        userService.unblockAccount(target.id());

        log.info("Odblokowano konto (id={}, przez id={})", targetId, adminId);
        return get(targetId);
    }

    /**
     * Zdejmuje automatyczną blokadę nałożoną po serii nieudanych logowań.
     *
     * Operacja nie rusza blokady administracyjnej i na tym polega sens trzymania obu stanów
     * w osobnych kolumnach: gdyby kara administracyjna była zapisana w terminie blokady
     * logowania, zdejmowałaby ją mimochodem ta akcja, a także każde udane logowanie i każdy
     * reset hasła.
     */
    @Transactional
    public AdminUserView clearLoginLock(Long targetId, Long adminId) {
        AdminUserView target = get(targetId);
        userService.clearLoginLock(target.id());

        log.info("Zdjęto blokadę po nieudanych logowaniach (id={}, przez id={})", targetId, adminId);
        return get(targetId);
    }

    /**
     * Unieważnia wszystkie sesje konta, nie blokując go.
     *
     * Akcja jest osobna od blokady, bo odpowiada na inną sytuację - użytkownik zgłasza, że
     * pozostawił się zalogowanym na cudzym urządzeniu. Gdyby przy okazji blokowała konto, pomoc
     * kończyłaby się karą.
     *
     * Token dostępowy tego użytkownika pozostaje ważny do piętnastu minut, ponieważ filtr JWT
     * sprawdza blokadę konta, a nie stan sesji - wiersz użytkownika nic o sesjach nie mówi.
     * Zmiana wymagałaby odpytywania bazy o sesje przy każdym żądaniu, czyli rezygnacji
     * z bezstanowego tokenu dostępowego. Komunikat we froncie mówi o tym wprost.
     */
    @Transactional
    public AdminUserView forceLogout(Long targetId, Long adminId) {
        AdminUserView target = get(targetId);
        int revoked = refreshTokenService.revokeAllSessions(target.id());

        log.info("Wymuszono wylogowanie (id={}, przez id={}, unieważniono {} token(ów))",
                targetId, adminId, revoked);
        return get(targetId);
    }

    /**
     * Kasuje konto razem z sesjami, tokenami resetu, zleceniami tłumaczenia i plikami.
     * Operacja jest nieodwracalna - nie ma kosza ani kolumny oznaczającej konto jako usunięte.
     *
     * Kasowanie jest osobną akcją od blokady, bo odpowiada na inne pytanie: blokada odcina
     * dostęp, ale zostawia na miejscu adres, imię, hash hasła i pliki. Bez tej operacji żądanie
     * usunięcia danych trzeba by realizować ręczną zmianą w bazie, czyli bez śladu i bez kontroli,
     * kto ją wykonał.
     *
     * Kolejność kontroli jest odwrotna niż przy blokadzie: tam pierwsza jest kontrola ostatniego
     * administratora, bo blokada własnego konta bywa dopuszczalna. Tutaj skasowanie własnego konta
     * jest odrzucane zawsze, więc komunikat o ostatnim administratorze byłby nieprawdą sugerującą,
     * że przy drugim administratorze operacja by przeszła.
     *
     * Kontrola ostatniego administratora wygląda na nieosiągalną, ale nią nie jest. Sekwencyjnie
     * faktycznie nie ma jak jej uruchomić, bo wołający jest niezablokowanym administratorem.
     * Zadziała współbieżnie: dwóch administratorów kasujących się nawzajem w tej samej chwili
     * przeszłoby obie kontrole działania na własnym koncie i nie zostałby żaden administrator,
     * czyli powstałby stan, z którego aplikacja sama się nie podniesie. Blokada wierszy ustawia
     * takie wywołania w kolejkę, więc drugie widzi wynik pierwszego i dostaje odmowę.
     *
     * Sesje nie są unieważniane osobno - wiersze tokenów znikają razem z kontem dzięki kaskadzie
     * klucza obcego. Żywy token dostępowy przestaje działać przy najbliższym żądaniu, ponieważ
     * warstwa uwierzytelniania nie odnajduje już konta.
     */
    public void delete(Long targetId, Long adminId) {
        transaction.executeWithoutResult(status -> {
            AdminUserView target = get(targetId);

            if (targetId.equals(adminId)) {
                throw new AdminActionRejectedException("CANNOT_DELETE_SELF",
                        "Nie można usunąć własnego konta");
            }

            if (target.role() == Role.ADMIN) {
                List<Long> unblockedAdmins = userService.lockUnblockedAdminIds();
                if (unblockedAdmins.contains(targetId) && unblockedAdmins.size() <= 1) {
                    throw new AdminActionRejectedException("LAST_ADMIN_CANNOT_BE_DELETED",
                            "To jedyne niezablokowane konto administratora - jego usunięcie "
                                    + "odcięłoby dostęp do panelu wszystkim");
                }
            }

            if (!userService.deleteAccount(targetId)) {
                // Konto zniknęło między odczytem a kasowaniem - dla wołającego jest to ten sam
                // przypadek co brak konta.
                throw new AdminUserNotFoundException();
            }
        });

        /*
         * Kasowanie plików następuje po zatwierdzeniu transakcji z dwóch powodów: wywołanie po
         * sieci nie może trzymać połączenia z puli, a wycofanie transakcji po usunięciu plików
         * zostawiłoby konto ze zleceniami bez treści.
         *
         * Awaria magazynu nie przewraca całej operacji. Konto jest już skasowane i cofnąć się nie
         * da, więc wyjątek puszczony dalej dałby administratorowi błąd 500 po udanym usunięciu,
         * a ponowienie odpowiedziałoby wtedy "nie ma takiego konta". Zamiast tego powstaje wpis
         * ostrzegawczy: pliki są już nieosiągalne, bo nie wskazuje ich żaden wiersz, i wygasną
         * przez regułę na kubełku, tak samo jak pliki osierocone przez retencję.
         */
        try {
            translationService.deleteAllFilesOf(targetId);
        } catch (RuntimeException e) {
            log.warn("Konto usunięte, ale nie udało się skasować jego plików (id={}): {}",
                    targetId, e.toString());
        }

        log.info("Usunięto konto razem z danymi (id={}, przez id={})", targetId, adminId);
    }
}
