/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.robert.auth.repository;

import com.example.robert.auth.model.PendingRegistration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
public interface PendingRegistrationRepository extends JpaRepository<PendingRegistration, Long> {

    Optional<PendingRegistration> findByTokenHash(String tokenHash);

    /**
     * Kasuje wszystkie zgłoszenia na dany adres. Wołane po udanym potwierdzeniu:
     * konto już istnieje, więc pozostałe zgłoszenia na ten adres (także obce) tracą sens
     * i nie mają prawa dalej wisieć w poczekalni.
     */
    @Modifying
    @Query("delete from PendingRegistration p where p.email = :email")
    int deleteAllByEmail(@Param("email") String email);

    @Modifying
    @Query("delete from PendingRegistration p where p.expiresAt < :now")
    int deleteAllExpired(@Param("now") Instant now);
}
