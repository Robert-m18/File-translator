package com.example.robert.translation;

import com.example.robert.common.security.TokenHasher;
import com.example.robert.common.time.DbClock;
import com.example.robert.translation.model.FileType;
import com.example.robert.translation.model.TargetLanguage;
import com.example.robert.translation.model.TranslationJob;
import com.example.robert.translation.storage.ObjectKeys;
import com.example.robert.translation.storage.ObjectStore;
import com.example.robert.user.UserRepository;
import com.example.robert.user.model.Role;
import com.example.robert.user.model.User;
import jakarta.servlet.http.Cookie;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
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

    /**
     * Zlecenie z treścią wgraną do magazynu obiektowego - gotowe do zapisania w repozytorium.
     *
     * Wydzielone tutaj, bo od czasu przejścia na magazyn obiektowy zbudowanie zlecenia "z
     * treścią" to DWIE czynności, które muszą iść razem i w tej kolejności: najpierw plik,
     * potem wiersz wskazujący na jego klucz. Rozpisane w każdej klasie z osobna byłoby
     * trzema miejscami, w których da się o tej kolejności zapomnieć - a test z wierszem
     * bez pliku nie wywala się przy zapisie, tylko dużo później, przy próbie tłumaczenia.
     *
     * Odwzorowuje to, co robi TranslationService.submit, ale świadomie NIE woła go: te testy
     * sprawdzają workera i retencję, więc muszą móc ustawić stan wprost, bez przechodzenia
     * przez limit dobowy i deduplikację.
     */
    static TranslationJob storedJob(ObjectStore objectStore,
                                    User owner,
                                    String filename,
                                    TargetLanguage targetLang,
                                    String content) {

        String sourceKey = ObjectKeys.sourceKey(
                ObjectKeys.jobPrefix(owner.getId(), ObjectKeys.newStorageId()), ".txt");
        objectStore.put(sourceKey, content.getBytes(StandardCharsets.UTF_8), "text/plain; charset=UTF-8");

        return new TranslationJob(owner, filename, targetLang, FileType.TXT, sourceKey,
                TokenHasher.sha256Hex(content), content.length(), DbClock.now(), false);
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
