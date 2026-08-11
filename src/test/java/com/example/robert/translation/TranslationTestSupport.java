package com.example.robert.translation;

import com.example.robert.user.UserRepository;
import com.example.robert.user.model.Role;
import com.example.robert.user.model.User;
import jakarta.servlet.http.Cookie;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Arrays;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Wspólne przygotowanie dla testów tłumaczenia: konto i ciasteczko z tokenem.
 *
 * Wydzielone, bo tego samego potrzebują cztery klasy testowe, a przepisanie tworzenia
 * użytkownika i logowania w każdej z nich znaczyłoby, że zmiana w polityce haseł wymaga
 * czterech identycznych poprawek. Świadomie NIE jest to klasa bazowa: dziedziczenie
 * w testach ciągnie za sobą cały kontekst rodzica i utrudnia czytanie pojedynczego testu.
 *
 * Logujemy się przez prawdziwy POST /auth/login, a nie przez podstawienie principala:
 * ścieżka, którą sprawdzamy, prowadzi przez JwtFilter i to on ma wstawić użytkownika.
 */
final class TranslationTestSupport {

    static final String PASSWORD = "PoprawneHaslo1";

    private TranslationTestSupport() {
    }

    static User createUser(UserRepository users, PasswordEncoder encoder, String email, String name) {
        users.findByEmail(email).ifPresent(users::delete);

        User user = new User();
        user.setName(name);
        user.setEmail(email);
        user.setPassword(encoder.encode(PASSWORD));
        user.setRole(Role.USER);
        user.setEnabled(true);
        return users.save(user);
    }

    static Cookie login(MockMvc mockMvc, String email) throws Exception {
        String body = """
                {"email":"%s","password":"%s"}
                """.formatted(email, PASSWORD);

        MvcResult result = mockMvc.perform(post("/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();

        return Arrays.stream(result.getResponse().getCookies())
                .filter(cookie -> "accessToken".equals(cookie.getName()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Logowanie nie zwróciło ciasteczka accessToken"));
    }
}
