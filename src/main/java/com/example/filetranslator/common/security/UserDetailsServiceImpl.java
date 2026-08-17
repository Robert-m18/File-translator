/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.filetranslator.common.security;


import com.example.filetranslator.common.validation.EmailNormalizer;
import com.example.filetranslator.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserDetailsServiceImpl implements UserDetailsService {

    private final UserRepository userRepository;

    /**
     * Normalizacja adresu jest tu powtórzona świadomie, mimo że DTO wejściowe robią
     * to samo. Ta metoda ma drugie wywołanie, które nie przechodzi przez żadne DTO:
     * JwtFilter podaje wartość claimu "sub" z tokenu. Token wystawiony przed
     * wprowadzeniem normalizacji niesie adres w postaci, w jakiej użytkownik go
     * wpisał - bez tej linii taki token przestałby uwierzytelniać na PostgreSQL,
     * mimo poprawnego podpisu i niewygasłego terminu.
     */
    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        return userRepository.findByEmail(EmailNormalizer.normalize(email))
                .orElseThrow(() -> new UsernameNotFoundException(
                        "Nie znaleziono użytkownika: " + email));
    }
}
