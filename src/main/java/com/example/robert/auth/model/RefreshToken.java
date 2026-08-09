/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.robert.auth.model;

import com.example.robert.user.model.User;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Zapis wydanego tokenu odświeżającego.
 *
 * Sam JWT jest bezstanowy - raz podpisany, jest ważny aż do wygaśnięcia i serwer
 * nie ma jak go cofnąć. Ten rekord dokłada do niego stan po stronie serwera, dzięki
 * czemu wylogowanie faktycznie unieważnia sesję, a skradziony token da się odciąć.
 *
 * Access token świadomie zostaje bezstanowy - jest ważny 15 minut i sprawdzanie go
 * w bazie przy każdym żądaniu zabrałoby główną zaletę JWT.
 */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    // LAZY: przy walidacji tokenu prawie nigdy nie potrzebujemy całego użytkownika
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** Wspólny identyfikator wszystkich tokenów powstałych z rotacji jednej sesji. */
    @Column(name = "family_id", nullable = false, length = 36)
    private String familyId;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** NULL = token aktywny. Ustawiane przy rotacji, wylogowaniu i unieważnieniu rodziny. */
    @Column(name = "revoked_at")
    private Instant revokedAt;

    public RefreshToken(String tokenHash, User user, String familyId, Instant issuedAt, Instant expiresAt) {
        this.tokenHash = tokenHash;
        this.user = user;
        this.familyId = familyId;
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean isExpired() {
        return expiresAt.isBefore(Instant.now());
    }
}
