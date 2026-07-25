/*
 * Copyright (c) 2026 Robert Moczygęba. Wszelkie prawa zastrzeżone.
 * See LICENSE in project root for details.
 */
package com.example.robert.user;


import com.example.robert.user.dto.UserRequestDTO;
import com.example.robert.user.dto.UserResponseDTO;
import com.example.robert.user.model.User;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface UserMapper {

    // Mapowanie z DTO do encji — ignorujemy id, role, enabled, authorities (ustawiane w serwisie/Spring Security)
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "role", ignore = true)
    @Mapping(target = "enabled", ignore = true)
    @Mapping(target = "authorities", ignore = true)
    User toEntity(UserRequestDTO dto);

    // Mapowanie z encji do response DTO — MapStruct automatycznie mapuje tylko pasujące pola (id, name, email)
    // Pola password i authorities nie będą mapowane (bezpieczeństwo)
    UserResponseDTO toResponseDto(User user);
}
