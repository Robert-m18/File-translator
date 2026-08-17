/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.filetranslator.admin;

import com.example.filetranslator.admin.dto.BlockUserRequest;
import com.example.filetranslator.user.dto.AdminUserView;
import com.example.filetranslator.user.model.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Panel administracyjny: przegląd kont i cztery akcje na nich.
 *
 * ŚCIEŻKA TO /users, A NIE /admin/users, i to nie jest kwestia gustu. SecurityConfig ma
 * regułę .requestMatchers("/users/**").hasRole("ADMIN") stojącą tam od czasu usunięcia
 * dawnego UserController właśnie po to, żeby przyszły kontroler był chroniony od pierwszego
 * commitu. Zmapowanie tych endpointów gdzie indziej wymagałoby nowego matchera, a do czasu
 * jego dodania panel wpadłby pod anyRequest().authenticated(), czyli stanąłby otworem dla
 * KAŻDEGO zalogowanego. Regresja: AdminPanelTest.regularUser_shouldGet403OnUserEndpoints.
 *
 * Akcje są POST-ami, więc wymagają nagłówka CSRF jak każda operacja zmieniająca stan.
 * Nie ma DELETE - kasowanie konta pociąga kaskady w refresh_tokens, password_reset_tokens
 * i translation_jobs (razem z plikami użytkownika) i zasługuje na własny projekt, a nie
 * na dopisek do panelu.
 *
 * Kontroler jest wyłącznie warstwą HTTP. Mapowanie wyjątków na kody stanu siedzi
 * w GlobalExceptionHandler.
 */
@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class AdminUserController {

    /**
     * Sufit rozmiaru strony, tak samo jak w TranslationController. Bez niego ?size=100000
     * zamienia listę w zapytanie zwracające wszystkie konta w systemie - jednym parametrem
     * w adresie, i to na endpoincie, którego odpowiedź niesie adresy email.
     */
    private static final int MAX_PAGE_SIZE = 100;

    private final AdminUserService adminUserService;

    /**
     * @param q fragment adresu email; pusty albo pominięty = wszystkie konta
     */
    @GetMapping
    public Page<AdminUserView> list(@RequestParam(name = "q", required = false) String q,
                                    Pageable pageable) {
        return adminUserService.list(q, capped(pageable));
    }

    @GetMapping("/{id}")
    public AdminUserView get(@PathVariable Long id) {
        return adminUserService.get(id);
    }

    @PostMapping("/{id}/block")
    public AdminUserView block(@PathVariable Long id,
                               @Valid @RequestBody BlockUserRequest request,
                               @AuthenticationPrincipal User admin) {
        return adminUserService.block(id, admin.getId(), request.reason());
    }

    @PostMapping("/{id}/unblock")
    public AdminUserView unblock(@PathVariable Long id,
                                 @AuthenticationPrincipal User admin) {
        return adminUserService.unblock(id, admin.getId());
    }

    /** Zdejmuje blokadę po nieudanych logowaniach - NIE blokadę administracyjną. */
    @PostMapping("/{id}/unlock")
    public AdminUserView unlock(@PathVariable Long id,
                                @AuthenticationPrincipal User admin) {
        return adminUserService.clearLoginLock(id, admin.getId());
    }

    /** Zrywa sesje bez blokowania konta. */
    @PostMapping("/{id}/logout")
    public AdminUserView forceLogout(@PathVariable Long id,
                                     @AuthenticationPrincipal User admin) {
        return adminUserService.forceLogout(id, admin.getId());
    }

    private Pageable capped(Pageable pageable) {
        if (pageable.getPageSize() <= MAX_PAGE_SIZE) {
            return pageable;
        }
        return PageRequest.of(pageable.getPageNumber(), MAX_PAGE_SIZE, pageable.getSort());
    }
}
