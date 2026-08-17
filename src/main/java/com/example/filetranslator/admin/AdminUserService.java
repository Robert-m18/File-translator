/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.filetranslator.admin;

import com.example.filetranslator.admin.exception.AdminActionRejectedException;
import com.example.filetranslator.admin.exception.AdminUserNotFoundException;
import com.example.filetranslator.auth.RefreshTokenService;
import com.example.filetranslator.user.UserService;
import com.example.filetranslator.user.dto.AdminUserView;
import com.example.filetranslator.user.model.Role;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
@RequiredArgsConstructor
public class AdminUserService {

    private final UserService userService;
    private final RefreshTokenService refreshTokenService;

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
}
