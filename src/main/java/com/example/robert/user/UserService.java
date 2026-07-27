/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.robert.user;

import com.example.robert.user.dto.UserRequestDTO;
import com.example.robert.user.dto.UserResponseDTO;
import com.example.robert.common.exception.EmailAlreadyExistException;
import com.example.robert.common.exception.NotFoundException;
import com.example.robert.user.UserMapper;
import com.example.robert.user.model.Role;
import com.example.robert.user.model.User;
import com.example.robert.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;


@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    @Transactional(readOnly = true)
    public Page<UserResponseDTO> getAllUsers(Pageable pageable) {
        return userRepository.findAll(pageable)
                .map(userMapper::toResponseDto);
    }

    /**
     * Zakłada konto już potwierdzone, na podstawie zgłoszenia z poczekalni.
     *
     * Hasło przychodzi ZAHASHOWANE - BCrypt policzono już przy przyjęciu zgłoszenia,
     * więc kodowanie go tutaj po raz drugi dałoby hash hasha i uniemożliwiło logowanie.
     *
     * Nie ma tu odpowiednika dawnego saveUser(dto) zakładającego konto z enabled=false.
     * Taki wiersz jest teraz stanem niemożliwym: konto powstaje wyłącznie w chwili
     * potwierdzenia adresu, a wcześniej dane leżą w pending_registrations.
     */
    @Transactional
    public User createConfirmedUser(String email, String name, String passwordHash) {
        User user = new User();
        user.setEmail(email);
        user.setName(name);
        user.setPassword(passwordHash);
        user.setRole(Role.USER);
        user.setEnabled(true);
        return userRepository.save(user);
    }

    @Transactional(readOnly = true)
    public UserResponseDTO getUser(Long id) {
        return userMapper.toResponseDto(
                userRepository.findById(id)
                        .orElseThrow(() ->
                                new NotFoundException(
                                        "User not found with id: " + id
                                )
                        )
        );
    }

    @Transactional
    public void deleteUser(Long id) {
        if (!userRepository.existsById(id)) {
            throw new NotFoundException("User not found with id: " + id);
        }
        userRepository.deleteById(id);
    }

    @Transactional
    public void updateUser(Long id, UserRequestDTO dto) {

        User user = userRepository.findById(id)
                .orElseThrow(() ->
                        new NotFoundException(
                                "User not found with id: " + id
                        )
                );

        // Bez tego sprawdzenia zmiana emaila na już zajęty leciała aż do bazy,
        // a naruszenie unikalności wracało do klienta jako 500 zamiast 409.
        if (!user.getEmail().equals(dto.email()) && userRepository.existsByEmail(dto.email())) {
            throw new EmailAlreadyExistException("User with this email already exists!");
        }

        user.setName(dto.name());
        user.setEmail(dto.email());
        user.setPassword(passwordEncoder.encode(dto.password()));

        userRepository.save(user);
    }

    @Transactional(readOnly = true)
    public boolean existsByEmail(String email) {
        return userRepository.existsByEmail(email);
    }

    @Transactional(readOnly = true)
    public Optional<User> findEntityByEmail(String email) {
        return userRepository.findByEmail(email);
    }

    /**
     * Podmienia hash hasła. Przyjmuje wartość już zakodowaną, bo kodowanie należy do
     * tego, kto zna hasło jawne - tutaj trafia wyłącznie gotowy hash.
     */
    @Transactional
    public void updatePassword(User user, String newPasswordHash) {
        user.setPassword(newPasswordHash);
        userRepository.save(user);
    }

    /** Zeruje licznik nieudanych logowań i zdejmuje blokadę. Wołane po resecie hasła. */
    @Transactional
    public void clearLoginFailures(String email) {
        userRepository.resetFailedLoginAttempts(email);
    }
}