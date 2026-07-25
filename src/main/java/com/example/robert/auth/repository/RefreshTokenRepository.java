/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.robert.auth.repository;

import com.example.robert.auth.model.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
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
    int revokeFamily(@Param("familyId") String familyId, @Param("now") LocalDateTime now);

    /** Sprzątanie po wygasłych tokenach - i tak nie da się ich już użyć. */
    @Modifying
    @Query("delete from RefreshToken t where t.expiresAt < :now")
    int deleteAllExpired(@Param("now") LocalDateTime now);
}
