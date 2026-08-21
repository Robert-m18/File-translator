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
 * Operacje panelu administracyjnego na kontach użytkowników.
 *
 * DLACZEGO OSOBNY PAKIET admin/, A NIE METODY W user/
 *
 * Zablokowanie konta musi zerwać jego sesje, czyli wołać RefreshTokenService z pakietu auth/.
 * Umieszczenie tego w user/ dałoby zależność user -> auth i odwróciło regułę "auth zależy
 * od user, nigdy odwrotnie", zamieniając ją w cykl. Port z jedną implementacją jest
 * wykluczony inną regułą tego projektu (interfejsy tylko tam, gdzie naprawdę są dwie
 * implementacje). Pakiet stojący NAD oboma zostawia strzałki acykliczne:
 * admin -> user, admin -> auth, auth -> user.
 *
 * admin/ NIE DOTYKA UserRepository - wszystkie zapytania o tabelę users idą przez UserService.
 * Sięganie do repozytorium cudzego pakietu jest w tym projekcie odrzucone wprost (patrz
 * uzasadnienie umiejscowienia OutboxCleanupJob).
 *
 * LOGI: id celu i id administratora, nigdy adres email ani powód blokady. Powód jest treścią
 * podaną przez człowieka o innym człowieku - w logu byłby daną osobową bez terminu ważności.
 */
@Slf4j
@Service
public class AdminUserService {

    private final UserService userService;
    private final RefreshTokenService refreshTokenService;

    /**
     * Kasowanie plików usuwanego konta. To JEDYNE miejsce, w którym admin/ sięga do
     * translation/ - i sięga po port, a nie po repozytorium tamtego pakietu, tak samo jak
     * po auth/ sięga przez RefreshTokenService. Strzałki zostają acykliczne:
     * admin -> translation -> user.
     */
    private final TranslationService translationService;

    /**
     * Transakcja wołana programowo, wyłącznie na potrzeby kasowania konta: kasowanie plików
     * MUSI zostać poza nią (to wywołanie po sieci), a decyzja o kasowaniu MUSI być w środku,
     * bo trzyma ją blokada wierszy. @Transactional na metodzie tej klasy nie dałby rady
     * rozdzielić jednego od drugiego, a wołanie własnej metody i tak omija proxy Springa.
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
     * Blokuje konto i NATYCHMIAST zrywa jego sesje.
     *
     * Samo ustawienie blocked_at nie wystarcza i nie jest to szczegół: token odświeżający
     * żyje 7 dni, więc bez revokeAllSessions zablokowany odnawiałby sobie dostęp przez
     * tydzień (kontrolę stanu w AuthService.refreshToken traktujemy jako drugą, niezależną
     * warstwę - ta tutaj usuwa same tokeny). Regresja: block_shouldRevokeAllRefreshTokens.
     *
     * Żywy token DOSTĘPOWY przestaje działać przy najbliższym żądaniu dzięki sprawdzeniu
     * w JwtFilter - to jedyne miejsce, w którym 15-minutowe okno tokenu dostępowego zostało
     * w tym projekcie domknięte, i wolno je było domknąć tylko dlatego, że filtr i tak
     * czyta wiersz użytkownika przy każdym żądaniu.
     */
    @Transactional
    public AdminUserView block(Long targetId, Long adminId, String reason) {
        AdminUserView target = get(targetId);

        /*
         * Kontrola "ostatniego administratora" PRZED kontrolą "nie blokuj siebie", choć
         * praktycznie odpalić może się tylko przy blokowaniu samego siebie: wołający jest
         * z definicji niezablokowanym administratorem, więc dopóki celem jest ktoś inny,
         * niezablokowani są co najmniej dwaj. Kolejność decyduje o KOMUNIKACIE i dlatego
         * nie jest dowolna - "zostałbyś ostatnim administratorem" nazywa prawdziwy problem
         * (nie ma drogi powrotnej do roli ADMIN, bo AdminBootstrap z założenia nie promuje
         * istniejącego konta USER), a "nie możesz zablokować siebie" brzmi jak drobna
         * niewygoda, którą da się obejść z drugiego konta.
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
         * Rewokacja leci TAKŻE przy powtórnym zablokowaniu już zablokowanego konta.
         * Wygląda na zbędną pracę, a jest odpowiedzią na wyścig: sesja mogła powstać
         * między pierwszą blokadą a tym wywołaniem, gdyby pierwsze zawiodło w połowie.
         * Kosztuje jeden UPDATE po indeksie, a alternatywą jest blokada bez skutku.
         */
        int revoked = refreshTokenService.revokeAllSessions(targetId);

        log.info("Zablokowano konto (id={}, przez id={}, nowa blokada={}, "
                        + "unieważniono {} token(ów) odświeżających)",
                targetId, adminId, changed, revoked);

        return get(targetId);
    }

    @Transactional
    public AdminUserView unblock(Long targetId, Long adminId) {
        AdminUserView target = get(targetId);
        userService.unblockAccount(target.id());

        // Bez wystawiania nowych tokenów - odblokowany po prostu loguje się na nowo.
        log.info("Odblokowano konto (id={}, przez id={})", targetId, adminId);
        return get(targetId);
    }

    /**
     * Zdejmuje automatyczną blokadę po nieudanych logowaniach.
     *
     * NIE rusza blokady administracyjnej i to jest cały sens rozdzielenia tych dwóch stanów
     * na osobne kolumny: gdyby kara siedziała w locked_until, ta akcja - i każdy udany login,
     * i każdy reset hasła - zdejmowałaby ją mimochodem. Regresja:
     * clearingLoginFailures_shouldNotLiftAdminBlock.
     */
    @Transactional
    public AdminUserView clearLoginLock(Long targetId, Long adminId) {
        AdminUserView target = get(targetId);
        userService.clearLoginLock(target.id());

        log.info("Zdjęto blokadę po nieudanych logowaniach (id={}, przez id={})", targetId, adminId);
        return get(targetId);
    }

    /**
     * Zrywa wszystkie sesje konta, NIE blokując go.
     *
     * Osobna akcja od blokady, bo odpowiada na inną sytuację: użytkownik zgłasza, że
     * zostawił się zalogowanym na cudzym komputerze. Gdyby robiła przy okazji blokadę,
     * pomoc kończyłaby się karą. Regresja: forceLogout_shouldKillSessionsWithoutBlocking.
     *
     * Uwaga do komunikatu we froncie: token DOSTĘPOWY tego użytkownika działa jeszcze
     * do 15 minut, bo JwtFilter sprawdza blokadę, a nie zerwane sesje - wiersz użytkownika
     * nic o nich nie mówi. Nie da się tego zmienić bez odpytywania bazy o sesje przy każdym
     * żądaniu, czyli bez rezygnacji z bezstanowego tokenu dostępowego.
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
     * NIEODWRACALNIE - nie ma tu kosza ani kolumny "usunięte".
     *
     * DLACZEGO KASOWANIE, SKORO JEST BLOKADA: blokada odcina dostęp, ale zostawia wszystko
     * na miejscu - adres, imię, hash hasła i pliki. Prawo do usunięcia danych trzeba było
     * dotąd realizować ręcznym UPDATE'em w bazie, czyli operacją bez śladu i bez kontroli,
     * kto ją wykonał. Blokada zostaje osobną akcją, bo odpowiada na inne pytanie
     * ("odciąć dostęp") niż kasowanie ("usunąć dane").
     *
     * KOLEJNOŚĆ KONTROLI JEST ODWROTNA NIŻ PRZY BLOKADZIE i to nie jest niekonsekwencja.
     * Tam "ostatni administrator" idzie pierwszy, bo blokada SIEBIE bywa dopuszczalna
     * (gdy administratorów jest dwóch) i chodziło o trafniejszy komunikat. Tutaj skasowanie
     * siebie jest odrzucane ZAWSZE, więc komunikat o ostatnim administratorze byłby
     * nieprawdą sugerującą, że przy drugim administratorze operacja by przeszła.
     *
     * KONTROLA OSTATNIEGO ADMINISTRATORA WYGLĄDA NA MARTWĄ I NIĄ NIE JEST. Sekwencyjnie
     * faktycznie nie ma jak jej odpalić: wołający jest niezablokowanym administratorem,
     * więc gdy celem jest ktoś inny, niezablokowani są co najmniej dwaj. Odpala się dopiero
     * WSPÓŁBIEŻNIE - dwóch administratorów kasujących się nawzajem w tej samej chwili
     * przeszłoby obie kontrole "nie kasuję siebie" i zostałoby zero administratorów, czyli
     * stan, z którego aplikacja sama nie wstanie (AdminBootstrap z założenia nie promuje
     * istniejącego konta USER). Blokada wierszy w lockUnblockedAdminIds ustawia takich
     * dwóch w kolejkę: drugi widzi wynik pierwszego i dostaje odmowę.
     *
     * Sesji nie unieważniamy osobno - wiersze refresh_tokens znikają z kaskadą klucza
     * obcego. Żywy token DOSTĘPOWY przestaje działać przy najbliższym żądaniu, bo
     * UserDetailsServiceImpl nie odnajdzie już konta; to jedyne miejsce obok blokady,
     * w którym 15-minutowe okno tokenu bezstanowego jest domknięte.
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
                // Zniknęło między odczytem a kasowaniem - dla wołającego to ten sam
                // przypadek co "nie ma takiego konta".
                throw new AdminUserNotFoundException();
            }
        });

        /*
         * Po zatwierdzeniu, nie w transakcji. Dwa powody, oba już w tym projekcie zapadły:
         * wywołanie po sieci nie ma prawa trzymać połączenia z pulą, a gdyby transakcja
         * wycofała się po skasowaniu plików, zostałoby konto ze zleceniami bez treści.
         *
         * AWARIA MAGAZYNU NIE PRZEWRACA CAŁEJ OPERACJI. Konto jest już skasowane i cofnąć
         * się nie da, więc wyjątek puszczony dalej dałby administratorowi 500 po UDANYM
         * usunięciu - a ponowienie odpowiedziałoby wtedy 404. Zamiast tego zostaje WARN
         * z identyfikatorem: pliki są już nieosiągalne (nie ma wiersza, który by je
         * wskazywał) i wygaszą się przez regułę na kubełku, tak samo jak osierocone przez
         * retencję. To ta sama decyzja co w TranslationCleanupJob.
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
