/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.filetranslator.common.security;

import com.example.filetranslator.common.exception.AccountBlockedException;
import com.example.filetranslator.user.model.User;
import org.springframework.security.authentication.AccountStatusUserDetailsChecker;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsChecker;

/**
 * Sprawdzenie stanu konta wykonywane PRZED porównaniem hasła.
 *
 * Po co w ogóle istnieje, skoro User.isAccountNonLocked() uwzględnia już blockedAt:
 * ta metoda daje wyłącznie LockedException, czyli odpowiedź ACCOUNT_LOCKED - "spróbuj
 * ponownie później", co przy blokadzie administracyjnej jest po prostu nieprawdą. Ta
 * blokada nie mija sama. Checker rozróżnia oba stany i daje własny kod ACCOUNT_BLOCKED,
 * po którym rozgałęzia się front. Metoda encji zostaje jako siatka bezpieczeństwa.
 *
 * DLACZEGO NIE RZUCAĆ TEGO Z UserDetailsServiceImpl: DaoAuthenticationProvider opakowuje
 * każdy wyjątek z ładowania użytkownika - poza UsernameNotFoundException - w
 * InternalAuthenticationServiceException, więc zablokowany dostałby 500 zamiast 423.
 *
 * DELEGACJA DO AccountStatusUserDetailsChecker JEST OBOWIĄZKOWA. Ustawienie własnego
 * checkera przez setPreAuthenticationChecks ZASTĘPUJE domyślny, a to on odsiewa konta
 * wyłączone, wygasłe i zablokowane. Bez tej linii sprawdzalibyśmy jedną rzecz i przestali
 * sprawdzać wszystkie pozostałe - w tym isEnabled(), czyli potwierdzenie adresu.
 */
public class BlockedAccountChecker implements UserDetailsChecker {

    private final UserDetailsChecker defaultChecks = new AccountStatusUserDetailsChecker();

    @Override
    public void check(UserDetails userDetails) {
        // instanceof, a nie rzutowanie: principal jest naszą encją dopóki jedynym źródłem
        // tożsamości jest UserDetailsService. Po dołożeniu logowania przez Google będzie
        // tu OidcUser i rzutowanie wywaliłoby logowanie wszystkim.
        if (userDetails instanceof User user && user.isBlocked()) {
            throw new AccountBlockedException();
        }
        defaultChecks.check(userDetails);
    }
}
