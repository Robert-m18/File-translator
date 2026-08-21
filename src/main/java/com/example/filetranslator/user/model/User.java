/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.filetranslator.user.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "users", uniqueConstraints = {
        @UniqueConstraint(columnNames = "email")
})
@EqualsAndHashCode(of = "email")
public class User implements UserDetails {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Walidacja danych wejściowych znajduje się w DTO, nie w encji. Adnotacje walidacyjne
    // na encji byłyby tu wręcz szkodliwe: reguła długości hasła sprawdzałaby długość jego
    // skrótu, czyli wartości o stałej długości, a nie samego hasła.
    // Tutaj zostają wyłącznie ograniczenia schematu bazy, lustrzane wobec migracji.

    @Column(nullable = false, length = 50)
    private String name;

    @Column(nullable = false, unique = true, length = 255)
    private String email;

    @Column(nullable = false, length = 100)
    private String password;

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private Role role = Role.USER;

    // Czy adres konta został potwierdzony. Domyślnie nie - potwierdzenie jest wymagane.
    @Column(nullable = false)
    private boolean enabled = false;

    /** Licznik kolejnych nieudanych logowań. Zerowany po udanym logowaniu. */
    @Column(name = "failed_login_attempts", nullable = false)
    private int failedLoginAttempts = 0;

    /** Do kiedy trwa blokada po nieudanych logowaniach; wartość pusta oznacza brak blokady. */
    @Column(name = "locked_until")
    private Instant lockedUntil;

    /**
     * Kiedy administrator zablokował konto. NULL = konto niezablokowane.
     *
     * Osobne pole od lockedUntil, bo to dwa różne stany o różnym cyklu życia: tamten
     * wygasa sam i jest zerowany przy każdym udanym logowaniu oraz przy resecie hasła
     * (resetFailedLoginAttempts), ten zdejmuje wyłącznie administrator. Pełne
     * uzasadnienie w changesecie 0010-admin-account-block.xml.
     */
    @Column(name = "blocked_at")
    private Instant blockedAt;

    /** Powód blokady - ślad audytowy dla kolejnego administratora. Nie trafia do logów. */
    @Column(name = "blocked_reason", length = 255)
    private String blockedReason;

    /**
     * Identyfikator konta Google (roszczenie "sub"). NULL = konto założone hasłem.
     *
     * Tożsamością jest "sub", a NIE adres email: adres w koncie Google da się zmienić,
     * "sub" nie. Adres służy wyłącznie do pierwszego skojarzenia kont - potem liczy się
     * już tylko ta kolumna. Pełne uzasadnienie w changesecie 0014-users-google-account.xml.
     *
     * Konto z wypełnionym google_sub NADAL ma hash hasła (losowy, porzucony), więc nie ma
     * tu stanu "użytkownik bez hasła" do obsłużenia nigdzie indziej w kodzie.
     */
    @Column(name = "google_sub", unique = true, length = 255)
    private String googleSub;

    /**
     * Data założenia konta, ustawiana przez Hibernate przy pierwszym zapisie.
     *
     * Nie jest tu dla statystyk - to po niej ExpiredTokenCleanupJob poznaje konta
     * porzucone na etapie rejestracji. Bez niej niepotwierdzony wiersz siedziałby
     * w bazie bez końca, trzymając unikalny adres email i blokując rejestrację
     * prawdziwemu właścicielowi skrzynki.
     */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }
    @Override public String getPassword() { return password; }
    @Override public String getUsername() { return email; }
    @Override public boolean isAccountNonExpired() { return true; }

    /**
     * Spring Security woła tę metodę PRZED sprawdzeniem hasła i przy false rzuca
     * LockedException. Dzięki temu zablokowane konto nie kosztuje nawet
     * porównania hasha BCrypt - a to właśnie ono jest kosztowne przy ataku siłowym.
     *
     * Blokada wygasa sama po upływie lockedUntil, więc nie potrzeba zadania
     * odblokowującego konta w tle.
     *
     * Blokada administracyjna (blockedAt) jest tu tylko SIATKĄ BEZPIECZEŃSTWA - właściwy
     * komunikat 423 ACCOUNT_BLOCKED daje BlockedAccountChecker, wpięty przed sprawdzeniem
     * hasła. Gdyby ktoś kiedyś odpiął checker, konto zablokowane nadal nie wpuści nikogo,
     * tyle że pod kodem ACCOUNT_LOCKED. Odwrotna kolejność - poleganie wyłącznie na
     * checkerze - zostawiałaby otwarte konto przy każdym uwierzytelnieniu, które go omija.
     */
    @Override
    public boolean isAccountNonLocked() {
        return blockedAt == null && (lockedUntil == null || lockedUntil.isBefore(Instant.now()));
    }

    /**
     * Czy konto zostało zablokowane przez administratora.
     *
     * Stan odrębny od ogólnego stanu konta, ponieważ filtr uwierzytelniający sprawdza wyłącznie
     * ten. Gdyby sprawdzał stan ogólny, blokada po nieudanych logowaniach wyrzucałaby z aplikacji
     * użytkownika z żywą sesją - a wywołać ją może każdy, wpisując kilka razy złe hasło
     * na cudzy adres - byłoby to gotowe narzędzie do wyrzucania zalogowanych użytkowników.
     */
    public boolean isBlocked() {
        return blockedAt != null;
    }

    @Override public boolean isCredentialsNonExpired() { return true; }
    @Override public boolean isEnabled() { return enabled; }
}