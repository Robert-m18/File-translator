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
import com.example.robert.user.model.User;
import com.example.robert.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


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

    @Transactional
    public User saveUser(UserRequestDTO dto) {

        User user = userMapper.toEntity(dto);

        user.setPassword(
                passwordEncoder.encode(dto.password())
        );
        user.setEnabled(false);

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
    public boolean userExistsByEmail(String email) {
        return userRepository.existsByEmail(email);
    }
}