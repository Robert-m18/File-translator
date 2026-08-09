/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.robert.user.model;

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

    // Walidacja danych wejściowych siedzi w DTO (UserRequestDTO), nie w encji.
    // Adnotacje bean-validation na encji działały tu wręcz szkodliwie: @Size(min=8)
    // na polu password sprawdzało długość hasha BCrypt (zawsze 60 znaków), a nie hasła.
    // Tutaj zostają wyłącznie ograniczenia schematu bazy - lustrzane wobec migracji Liquibase.

    @Column(nullable = false, length = 50)
    private String name;

    @Column(nullable = false, unique = true, length = 255)
    private String email;

    @Column(nullable = false, length = 100)
    private String password;

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private Role role = Role.USER;

    // Czy konto zostało potwierdzone emailem. Domyślnie false - wymagane potwierdzenie.
    @Column(nullable = false)
    private boolean enabled = false;

    /** Licznik kolejnych nieudanych logowań. Zerowany po udanym logowaniu. */
    @Column(name = "failed_login_attempts", nullable = false)
    private int failedLoginAttempts = 0;

    /** Do kiedy konto jest zablokowane. NULL = odblokowane. */
    @Column(name = "locked_until")
    private Instant lockedUntil;

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
     * LockedException. Dzięki temu zablokowane konto nie kosztuje nas nawet
     * porównania hasha BCrypt - a to właśnie ono jest kosztowne przy ataku siłowym.
     *
     * Blokada wygasa sama po upływie lockedUntil, więc nie potrzeba zadania
     * odblokowującego konta w tle.
     */
    @Override
    public boolean isAccountNonLocked() {
        return lockedUntil == null || lockedUntil.isBefore(Instant.now());
    }

    @Override public boolean isCredentialsNonExpired() { return true; }
    @Override public boolean isEnabled() { return enabled; }
}