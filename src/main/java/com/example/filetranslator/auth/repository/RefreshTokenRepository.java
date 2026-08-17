/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.filetranslator.auth.repository;

import com.example.filetranslator.auth.model.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * Unieważnia całą rodzinę tokenów jednym UPDATE-em.
     * Wołane przy wylogowaniu i po wykryciu ponownego użycia zużytego tokenu.
     */
    @Modifying
    @Query("update RefreshToken t set t.revokedAt = :now where t.familyId = :familyId and t.revokedAt is null")
    int revokeFamily(@Param("familyId") String familyId, @Param("now") Instant now);

    /**
     * Unieważnia WSZYSTKIE sesje użytkownika, niezależnie od rodziny i urządzenia.
     *
     * Wołane po resecie hasła. Jeśli powodem resetu było przejęcie konta, to napastnik
     * ma ważny token odświeżający - bez tego kroku zmiana hasła nic mu nie odbiera
     * i zostaje w koncie do wygaśnięcia tokenu, czyli przez tydzień.
     */
    @Modifying
    @Query("update RefreshToken t set t.revokedAt = :now where t.user.id = :userId and t.revokedAt is null")
    int revokeAllForUser(@Param("userId") Long userId, @Param("now") Instant now);

    /** Sprzątanie po wygasłych tokenach - i tak nie da się ich już użyć. */
    @Modifying
    @Query("delete from RefreshToken t where t.expiresAt < :now")
    int deleteAllExpired(@Param("now") Instant now);
}
