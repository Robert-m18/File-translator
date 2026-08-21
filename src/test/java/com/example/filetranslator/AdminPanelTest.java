package com.example.filetranslator;

import com.example.filetranslator.translation.model.TargetLanguage;
import com.example.filetranslator.translation.model.TranslationJob;
import com.example.filetranslator.translation.repository.TranslationJobRepository;
import com.example.filetranslator.translation.storage.ObjectKeys;
import com.example.filetranslator.translation.storage.ObjectStore;
import com.example.filetranslator.user.UserRepository;
import com.example.filetranslator.user.model.Role;
import com.example.filetranslator.user.model.User;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static com.example.filetranslator.TestTime.sql;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Panel administracyjny: przegląd kont i pięć akcji na nich.
 *
 * Testy idą przez pełny łańcuch filtrów, a nie przez sam serwis, ponieważ połowa sprawdzanego
 * zachowania znajduje się właśnie w łańcuchu: reguła autoryzacji ścieżki, sprawdzenie blokady
 * w filtrze uwierzytelniającym i checker wpięty w mechanizm logowania. Serwis wywołany wprost
 * potwierdzałby wyłącznie, że zapytanie ustawia kolumnę.
 *
 * Test jednostkowy filtra nie pokrywa gałęzi blokady: podstawia on pod odczyt użytkownika
 * atrapę innego typu niż encja aplikacji, więc sprawdzenie typu jest tam zawsze fałszywe.
 * Test drugi w tej klasie jest jedynym miejscem, w którym ta gałąź jest w ogóle wykonywana.
 *
 * Klasa świadomie nie bierze własnego kontekstu Springa: każdy nowy kontekst to kolejna pula
 * połączeń trzymana do końca przebiegu, a limit połączeń bazy jest wspólny dla wszystkich
 * kontekstów. Nic tutaj nie wymaga innej konfiguracji.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminPanelTest {

    private static final String ADMIN_EMAIL = "panel-admin@example.com";
    private static final String ADMIN2_EMAIL = "panel-admin2@example.com";
    private static final String USER_EMAIL = "panel-user@example.com";
    private static final String PASSWORD = "PoprawneHaslo1";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TranslationJobRepository translationJobRepository;

    /**
     * Magazyn obiektów - w domyślnym przebiegu prawdziwy magazyn z kontenera, w wariancie bez
     * Dockera mapa w pamięci. Test kasowania konta sprawdza go przez ten sam port, którym pisze
     * aplikacja, więc obie implementacje odpowiadają na to samo pytanie.
     */
    @Autowired
    private ObjectStore objectStore;

    private Long adminId;
    private Long admin2Id;
    private Long userId;

    /**
     * Kasowane są wszystkie konta z rolą administratora, nie tylko założone przez tę klasę.
     *
     * Reguła ostatniego administratora liczy niezablokowanych administratorów w całej bazie,
     * a baza testowa żyje dłużej niż pojedynczy kontekst, więc konto zostawione przez inną klasę
     * zmieniałoby wynik zależnie od kolejności klas. Klucze obce kasują kaskadowo, więc sesje
     * i zlecenia znikają razem z kontem.
     */
    @BeforeEach
    void setUp() {
        userRepository.findAll().stream()
                .filter(u -> u.getRole() == Role.ADMIN)
                .forEach(userRepository::delete);
        userRepository.findByEmail(USER_EMAIL).ifPresent(userRepository::delete);

        adminId = createUser(ADMIN_EMAIL, "Administrator", Role.ADMIN).getId();
        admin2Id = createUser(ADMIN2_EMAIL, "Drugi Administrator", Role.ADMIN).getId();
        userId = createUser(USER_EMAIL, "Zwykły Użytkownik", Role.USER).getId();
    }

    private User createUser(String email, String name, Role role) {
        User user = new User();
        user.setName(name);
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode(PASSWORD));
        user.setRole(role);
        user.setEnabled(true);
        return userRepository.save(user);
    }

    // ---------- pomocnicze: logowanie i wołanie panelu ----------

    private MvcResult login(String email, String password) throws Exception {
        String body = """
                {"email":"%s","password":"%s"}
                """.formatted(email, password);

        return mockMvc.perform(post("/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    private Cookie cookieFrom(MvcResult result, String name) {
        return Arrays.stream(result.getResponse().getCookies())
                .filter(c -> name.equals(c.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Brak ciasteczka " + name + " w odpowiedzi"));
    }

    private Cookie accessCookie(String email) throws Exception {
        MvcResult result = login(email, PASSWORD);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return cookieFrom(result, "accessToken");
    }

    private ResultActions block(Cookie admin, Long targetId, String reason) throws Exception {
        return mockMvc.perform(post("/users/{id}/block", targetId)
                .with(csrf())
                .cookie(admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"reason":"%s"}
                        """.formatted(reason)));
    }

    private ResultActions action(Cookie admin, String path, Long targetId) throws Exception {
        return mockMvc.perform(post("/users/{id}/" + path, targetId)
                .with(csrf())
                .cookie(admin));
    }

    /** Blokada wpisana wprost do wiersza - bez rewokacji sesji, żeby odizolować kontrolę stanu. */
    private void blockRowDirectly(String email) {
        jdbcTemplate.update("update users set blocked_at = ?, blocked_reason = ? where email = ?",
                sql(Instant.now()), "wpis testowy", email);
    }

    private int liveRefreshTokens(Long ownerId) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from refresh_tokens where user_id = ? and revoked_at is null",
                Integer.class, ownerId);
        return count == null ? 0 : count;
    }

    // ---------- 1-8: egzekwowanie blokady ----------

    /**
     * Asercja obejmuje kod odpowiedzi, a nie sam status. Wariant oparty na kolumnie aktywności
     * konta dałby komunikat o niepotwierdzonym adresie, a wariant oparty na ogólnym stanie
     * konta - komunikat sugerujący, że blokada minie sama. Sprawdzenie samego statusu
     * przepuściłoby ten drugi wariant.
     */
    @Test
    @DisplayName("1. Zablokowany nie zaloguje się - 423 ACCOUNT_BLOCKED")
    void blockedUser_shouldBeRejectedAtLogin() throws Exception {
        blockRowDirectly(USER_EMAIL);

        String body = """
                {"email":"%s","password":"%s"}
                """.formatted(USER_EMAIL, PASSWORD);

        mockMvc.perform(post("/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.code").value("ACCOUNT_BLOCKED"));
    }

    /**
     * Blokada ma działać natychmiast. Bez sprawdzenia w filtrze uwierzytelniającym ciasteczko
     * wydane przed blokadą otwierałoby chronione endpointy jeszcze przez cały czas ważności
     * tokenu dostępowego.
     */
    @Test
    @DisplayName("2. Blokada unieważnia żywy token dostępowy przy następnym żądaniu")
    void blockedUser_liveAccessTokenShouldStopWorking() throws Exception {
        Cookie live = accessCookie(USER_EMAIL);

        mockMvc.perform(get("/auth/me").cookie(live))
                .andExpect(status().isOk());

        blockRowDirectly(USER_EMAIL);

        mockMvc.perform(get("/auth/me").cookie(live))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ACCOUNT_BLOCKED"));
    }

    /**
     * Luka, przez którą blokada byłaby nieskuteczna przez cały tydzień: zablokowany użytkownik
     * co kwadrans wymieniałby token na nowy i pracował dalej.
     *
     * Bez kontroli stanu konta przy odnawianiu sesji test nie jest po prostu czerwony: odpowiedź
     * niesie wtedy kod ponownego użycia tokenu, bo blokada unieważniła tokeny, czyli komunikat
     * mówiący użytkownikowi o kradzieży. Dlatego asercja sprawdza kod, a nie sam status.
     */
    @Test
    @DisplayName("3. Zablokowany nie odświeży sesji - 423 ACCOUNT_BLOCKED, nie REFRESH_TOKEN_REUSED")
    void blockedUser_shouldNotRefreshSession() throws Exception {
        MvcResult session = login(USER_EMAIL, PASSWORD);
        Cookie refresh = cookieFrom(session, "refreshToken");

        block(accessCookie(ADMIN_EMAIL), userId, "podejrzana aktywność")
                .andExpect(status().isOk());

        mockMvc.perform(post("/auth/refresh").with(csrf()).cookie(refresh))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.code").value("ACCOUNT_BLOCKED"));
    }

    /**
     * Ten sam wynik przy żywej sesji: blokada wpisana wprost do wiersza, tokeny nietknięte.
     * Test izoluje kontrolę stanu konta od unieważnienia sesji - bez niego poprzedni przypadek
     * przechodziłby na samym unieważnieniu tokenów, a kontroli stanu mogłoby nie być.
     * Odwzorowuje też wyścig: sesję założoną po nałożeniu blokady, a przed unieważnieniem sesji.
     */
    @Test
    @DisplayName("4. Zablokowany wiersz z żywym tokenem też nie odświeży sesji")
    void blockedRowWithLiveSession_shouldStillFailRefresh() throws Exception {
        MvcResult session = login(USER_EMAIL, PASSWORD);
        Cookie refresh = cookieFrom(session, "refreshToken");

        blockRowDirectly(USER_EMAIL);
        assertThat(liveRefreshTokens(userId)).isEqualTo(1);

        mockMvc.perform(post("/auth/refresh").with(csrf()).cookie(refresh))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.code").value("ACCOUNT_BLOCKED"));
    }

    /**
     * Blokada ma unieważniać sesje, a nie tylko zamykać dostęp na przyszłość. Poprzednie testy
     * przechodzą na samej kontroli stanu, więc bez tego przypadku usunięcie unieważniania sesji
     * z panelu przeszłoby niezauważone - do chwili, w której ktoś zdejmie blokadę i odkryje,
     * że stara sesja nadal działa.
     */
    @Test
    @DisplayName("5. Blokada unieważnia wszystkie tokeny odświeżające")
    void block_shouldRevokeAllRefreshTokens() throws Exception {
        login(USER_EMAIL, PASSWORD);
        login(USER_EMAIL, PASSWORD); // druga sesja = druga rodzina tokenów
        assertThat(liveRefreshTokens(userId)).isEqualTo(2);

        block(accessCookie(ADMIN_EMAIL), userId, "regulamin")
                .andExpect(status().isOk());

        assertThat(liveRefreshTokens(userId)).isZero();
    }

    /**
     * Test czerwienieje, gdy blokada administracyjna zostanie przeniesiona do kolumny blokady
     * po nieudanych logowaniach. Zdjęcie tamtej blokady zeruje licznik prób i termin, więc kara
     * administracyjna trzymana w tym samym miejscu byłaby przy okazji zdejmowana - tak samo jak
     * przy każdym udanym logowaniu i każdym resecie hasła, czyli w wyniku działania ukaranego.
     */
    @Test
    @DisplayName("6. Zdjęcie blokady po nieudanych logowaniach nie zdejmuje blokady administracyjnej")
    void clearingLoginFailures_shouldNotLiftAdminBlock() throws Exception {
        blockRowDirectly(USER_EMAIL);
        jdbcTemplate.update("update users set failed_login_attempts = 3, locked_until = ? where email = ?",
                sql(Instant.now().plus(Duration.ofMinutes(15))), USER_EMAIL);

        action(accessCookie(ADMIN_EMAIL), "unlock", userId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failedLoginAttempts").value(0))
                .andExpect(jsonPath("$.lockedUntil").doesNotExist())
                .andExpect(jsonPath("$.blockedAt").isNotEmpty());

        // Konto nadal zamknięte - i nadal pod właściwym kodem
        mockMvc.perform(post("/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}
                                """.formatted(USER_EMAIL, PASSWORD)))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.code").value("ACCOUNT_BLOCKED"));
    }

    /**
     * Kontrola negatywna, najważniejsza w tej klasie.
     *
     * Test czerwienieje dokładnie wtedy, gdy filtr uwierzytelniający zacznie sprawdzać ogólny
     * stan konta zamiast samej blokady administracyjnej. Blokadę po nieudanych logowaniach może
     * wywołać każdy, kto zna cudzy adres i poda kilka razy złe hasło - gdyby unieważniała żywe
     * sesje, byłaby gotowym narzędziem do wyrzucania dowolnego zalogowanego użytkownika.
     */
    @Test
    @DisplayName("7. Blokada po nieudanych logowaniach NIE zrywa żywej sesji")
    void failedLoginLockout_shouldNotKillLiveSession() throws Exception {
        Cookie live = accessCookie(USER_EMAIL);

        // Profil testowy: próg 3 nieudanych prób
        for (int i = 0; i < 3; i++) {
            login(USER_EMAIL, "ZupelnieZleHaslo9");
        }
        assertThat(userRepository.findByEmail(USER_EMAIL).orElseThrow().getLockedUntil()).isNotNull();

        mockMvc.perform(get("/auth/me").cookie(live))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("8. Odblokowanie przywraca logowanie")
    void unblock_shouldRestoreLogin() throws Exception {
        Cookie admin = accessCookie(ADMIN_EMAIL);
        block(admin, userId, "pomyłka").andExpect(status().isOk());

        action(admin, "unblock", userId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blockedAt").doesNotExist())
                .andExpect(jsonPath("$.blockedReason").doesNotExist());

        assertThat(login(USER_EMAIL, PASSWORD).getResponse().getStatus()).isEqualTo(200);
    }

    // ---------- 9-11: reguły panelu i dostęp ----------

    /**
     * Drugi administrator w bazie jest tu konieczny: przy jednym odpaliłaby się reguła
     * ostatniego administratora i test sprawdzałby nie to, co ma w nazwie.
     */
    @Test
    @DisplayName("9. Administrator nie zablokuje samego siebie")
    void admin_shouldNotBlockSelf() throws Exception {
        block(accessCookie(ADMIN_EMAIL), adminId, "eksperyment")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CANNOT_BLOCK_SELF"));

        assertThat(userRepository.findByEmail(ADMIN_EMAIL).orElseThrow().isBlocked()).isFalse();
    }

    /**
     * Przypadek wymaga drugiego konta administratora: dopiero jego zablokowanie sprawia, że
     * kolejna próba dotyczy ostatniego niezablokowanego administratora. Przy jednym koncie test
     * przeszedłby na regule zakazującej działania na własnym koncie i niczego by nie rozróżniał.
     *
     * Test przypina też kolejność kontroli: reguła ostatniego administratora stoi przed regułą
     * zakazującą działania na własnym koncie, ponieważ nazywa prawdziwy skutek - do roli
     * administratora nie ma automatycznej drogi powrotnej, więc wyjściem byłaby ręczna zmiana
     * w bazie.
     */
    @Test
    @DisplayName("10. Ostatniego niezablokowanego administratora nie da się zablokować")
    void lastAdmin_shouldNotBeBlocked() throws Exception {
        Cookie admin = accessCookie(ADMIN_EMAIL);

        block(admin, admin2Id, "odejście z firmy").andExpect(status().isOk());

        block(admin, adminId, "i mnie też")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LAST_ADMIN_CANNOT_BE_BLOCKED"));

        assertThat(userRepository.findByEmail(ADMIN_EMAIL).orElseThrow().isBlocked()).isFalse();
    }

    /**
     * Test pilnuje ścieżki, pod którą stoi kontroler. Reguła ograniczająca ten prefiks do roli
     * administratora obowiązuje niezależnie od kontrolera, więc zmapowanie panelu gdzie indziej
     * wpuściłoby pod regułę ogólną każdego zalogowanego użytkownika.
     *
     * Pierwsza asercja jest konieczna i nie powtarza innych testów: sprawdzenie, że zwykły
     * użytkownik dostaje odmowę, byłoby zielone także wtedy, gdyby pod tą ścieżką nie było
     * żadnego kontrolera, bo nieznana ścieżka pod tą regułą też kończy się odmową. Dopiero
     * powodzenie dla administratora dowodzi, że panel stoi tam, gdzie sięga reguła.
     */
    @Test
    @DisplayName("11. Panel stoi pod regułą ADMIN, a zwykły użytkownik dostaje 403")
    void regularUser_shouldGet403OnUserEndpoints() throws Exception {
        mockMvc.perform(get("/users").cookie(accessCookie(ADMIN_EMAIL)))
                .andExpect(status().isOk());

        Cookie user = accessCookie(USER_EMAIL);

        mockMvc.perform(get("/users").cookie(user))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        mockMvc.perform(get("/users/{id}", adminId).cookie(user))
                .andExpect(status().isForbidden());

        block(user, adminId, "próba przejęcia")
                .andExpect(status().isForbidden());

        // Reguła obejmuje ścieżkę, a nie metodę, ale asercja jest tania, a pomyłka w tym
        // miejscu oddałaby kasowanie kont każdemu zalogowanemu użytkownikowi.

        mockMvc.perform(delete("/users/{id}", adminId).with(csrf()).cookie(user))
                .andExpect(status().isForbidden());

        assertThat(userRepository.findById(adminId)).isPresent();
    }

    // ---------- 12-15: lista i wyszukiwanie ----------

    /**
     * Wiersz z wielkimi literami w adresie zapisywany jest wprost przez repozytorium, ponieważ
     * normalizacja działa w konstruktorach DTO, a takie konta pochodzą sprzed jej wprowadzenia.
     * Bez funkcji zmiany wielkości liter po stronie kolumny baza ich nie znajdzie, a administrator
     * zobaczy pustą listę zamiast konta, o które pytał.
     */
    @Test
    @DisplayName("12. Wyszukiwanie ignoruje wielkość liter po obu stronach")
    void search_shouldIgnoreCase() throws Exception {
        String mieszany = "Wielkie.Litery@Example.com";
        userRepository.findByEmail(mieszany).ifPresent(userRepository::delete);
        createUser(mieszany, "Konto Sprzed Normalizacji", Role.USER);

        Cookie admin = accessCookie(ADMIN_EMAIL);

        mockMvc.perform(get("/users").param("q", "wielkie.litery").cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].email").value(mieszany));

        mockMvc.perform(get("/users").param("q", "  WIELKIE.LITERY  ").cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1));

        userRepository.findByEmail(mieszany).ifPresent(userRepository::delete);
    }

    /**
     * Bez escapowania metaznaków "%" pasuje do wszystkiego, czyli filtr po cichu przestaje
     * filtrować - administrator dostaje pełną listę w przekonaniu, że to wynik wyszukiwania.
     * "_" jest tu drugim przypadkiem: pasowałby do dowolnego pojedynczego znaku.
     */
    @Test
    @DisplayName("13. Metaznaki LIKE traktowane dosłownie")
    void search_shouldTreatWildcardsLiterally() throws Exception {
        Cookie admin = accessCookie(ADMIN_EMAIL);

        mockMvc.perform(get("/users").param("q", "%").cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));

        // "panel_admin" nie istnieje - istnieje "panel-admin". Bez escapowania "_"
        // dopasowałoby myślnik i zwróciło dwa konta.
        mockMvc.perform(get("/users").param("q", "panel_admin").cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));
    }

    /**
     * Asercja idzie po surowej treści odpowiedzi, a nie po polach obiektu: przy zwróceniu
     * encji zamiast projekcji test sprawdzający pola nadal byłby zielony, a hash hasła
     * pojechałby do przeglądarki.
     */
    @Test
    @DisplayName("14. Lista nie wynosi hasha hasła ani wewnętrznych pól UserDetails")
    void list_shouldNotExposePasswordHash() throws Exception {
        // Z filtrem, a nie cała lista: kolejność jest po identyfikatorze rosnąco, a konta tej
        // klasy powstają jako ostatnie, więc na pierwszej stronie mogłoby ich nie być.
        String body = mockMvc.perform(get("/users").param("q", "panel-user").cookie(accessCookie(ADMIN_EMAIL)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body)
                .contains(USER_EMAIL)
                .doesNotContain("password")
                .doesNotContain("$2a$")  // prefiks hasha BCrypt
                .doesNotContain("authorities")
                .doesNotContain("enabled");
    }

    /**
     * Bez capped(Pageable) jeden parametr w adresie zamienia listę w zrzut wszystkich kont
     * systemu - razem z adresami email.
     */
    @Test
    @DisplayName("15. Rozmiar strony jest ograniczony z góry")
    void list_shouldCapPageSize() throws Exception {
        mockMvc.perform(get("/users").param("size", "5000").cookie(accessCookie(ADMIN_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(100));
    }

    // ---------- 16-17: pozostałe akcje ----------

    /**
     * Wymuszone wylogowanie odpowiada na inną sytuację niż blokada ("zostawiłem się
     * zalogowany na cudzym komputerze") - gdyby przy okazji blokowało konto, pomoc
     * kończyłaby się karą.
     */
    @Test
    @DisplayName("16. Wymuszone wylogowanie zrywa sesje, ale nie blokuje konta")
    void forceLogout_shouldKillSessionsWithoutBlocking() throws Exception {
        login(USER_EMAIL, PASSWORD);
        assertThat(liveRefreshTokens(userId)).isEqualTo(1);

        action(accessCookie(ADMIN_EMAIL), "logout", userId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blockedAt").doesNotExist());

        assertThat(liveRefreshTokens(userId)).isZero();
        // Konto działa dalej - użytkownik po prostu loguje się ponownie
        assertThat(login(USER_EMAIL, PASSWORD).getResponse().getStatus()).isEqualTo(200);
    }

    /**
     * Powtórna blokada nie nadpisuje śladu audytowego. Bez warunku wykluczającego konta już
     * przypadkowy dwuklik podmieniłby datę i powód na nowe, a informacja o tym, kiedy i za co
     * konto faktycznie padło, przepadłaby bez śladu.
     */
    @Test
    @DisplayName("17. Powtórna blokada nie nadpisuje daty ani powodu")
    void block_shouldBeIdempotent() throws Exception {
        Cookie admin = accessCookie(ADMIN_EMAIL);

        block(admin, userId, "pierwszy powód").andExpect(status().isOk());
        User first = userRepository.findByEmail(USER_EMAIL).orElseThrow();

        block(admin, userId, "drugi powód")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blockedReason").value("pierwszy powód"));

        User second = userRepository.findByEmail(USER_EMAIL).orElseThrow();
        assertThat(second.getBlockedAt()).isEqualTo(first.getBlockedAt());
        assertThat(second.getBlockedReason()).isEqualTo("pierwszy powód");
    }

    /** Powód blokady jest obowiązkowy - blokada bez śladu audytowego jest bezużyteczna. */
    @Test
    @DisplayName("Blokada bez powodu jest odrzucana")
    void block_shouldRequireReason() throws Exception {
        mockMvc.perform(post("/users/{id}/block", userId)
                        .with(csrf())
                        .cookie(accessCookie(ADMIN_EMAIL))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"   "}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        assertThat(userRepository.findByEmail(USER_EMAIL).orElseThrow().isBlocked()).isFalse();
    }

    /** Nieistniejące konto: 404 z kodem, nie 500 i nie puste ciało. */
    @Test
    @DisplayName("Akcja na nieistniejącym koncie zwraca 404 USER_NOT_FOUND")
    void unknownUser_shouldReturn404() throws Exception {
        long nieistniejace = unusedUserId();

        action(accessCookie(ADMIN_EMAIL), "unblock", nieistniejace)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));

        mockMvc.perform(delete("/users/{id}", nieistniejace)
                        .with(csrf())
                        .cookie(accessCookie(ADMIN_EMAIL)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
    }

    // ---------- 18-21: usuwanie konta ----------

    /**
     * Kasowanie konta usuwa je razem ze wszystkim, co od niego zależy.
     *
     * Cztery asercje zamiast jednej, bo każda pilnuje innego mechanizmu i każda mogłaby
     * zawieść osobno: wiersz konta usuwa zapytanie aplikacji, sesje i zlecenia znikają kaskadą
     * po stronie bazy, o której warstwa dostępu do danych nic nie wie, więc klucz obcy bez
     * kaskadowego kasowania przerwałby operację naruszeniem więzów albo zostawił osierocone
     * wiersze, a plik w magazynie obiektowym nie ma z tą transakcją nic wspólnego i wymaga
     * osobnego wywołania.
     *
     * Plik jest tu najważniejszy: to jedyna część, której nie załatwia żadna kaskada, a zarazem
     * ta, dla której kasowanie konta powstało. Bez wywołania kasującego pliki test czerwienieje
     * wyłącznie na ostatniej asercji, bo reszta przechodzi.
     */
    @Test
    @DisplayName("18. Usunięcie konta kasuje sesje, zlecenia i pliki użytkownika")
    void delete_shouldRemoveAccountWithAllItsData() throws Exception {
        login(USER_EMAIL, PASSWORD); // zakłada wiersz w refresh_tokens
        String objectKey = seedJobWithFile(userId);

        assertThat(rowsOwnedBy("refresh_tokens", userId)).isPositive();
        assertThat(rowsOwnedBy("translation_jobs", userId)).isPositive();
        assertThat(objectStore.exists(objectKey)).isTrue();

        mockMvc.perform(delete("/users/{id}", userId)
                        .with(csrf())
                        .cookie(accessCookie(ADMIN_EMAIL)))
                .andExpect(status().isNoContent());

        assertThat(userRepository.findByEmail(USER_EMAIL)).isEmpty();
        assertThat(rowsOwnedBy("refresh_tokens", userId)).isZero();
        assertThat(rowsOwnedBy("translation_jobs", userId)).isZero();
        assertThat(objectStore.exists(objectKey)).isFalse();
    }

    /**
     * Skasowane konto przestaje działać natychmiast, także z żywym ciasteczkiem.
     *
     * Asercja idzie po kodzie odpowiedzi, a nie po samym statusie, i na tym polega ten test.
     * Bez osobnej gałęzi dla nieodnalezionego konta filtr uwierzytelniający również odmawia,
     * ale kodem oznaczającym błąd przetwarzania tokenu, na którym front się nie rozgałęzia, więc
     * użytkownik zostaje na ekranie z komunikatem o wewnętrznej awarii zamiast wrócić na ekran
     * logowania. Sprawdzenie samego statusu przepuściłoby ten wariant.
     */
    @Test
    @DisplayName("19. Skasowane konto nie zaloguje się i traci żywą sesję")
    void deletedUser_shouldLoseAccessImmediately() throws Exception {
        Cookie live = accessCookie(USER_EMAIL);

        mockMvc.perform(get("/auth/me").cookie(live)).andExpect(status().isOk());

        mockMvc.perform(delete("/users/{id}", userId)
                        .with(csrf())
                        .cookie(accessCookie(ADMIN_EMAIL)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/auth/me").cookie(live))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        mockMvc.perform(post("/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}
                                """.formatted(USER_EMAIL, PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("BAD_CREDENTIALS"));
    }

    /**
     * Administrator nie skasuje własnego konta, niezależnie od tego, ilu jest administratorów.

     *
     * Różnica wobec blokady jest celowa i test ją utrwala: w przygotowaniu istnieje drugi
     * administrator, więc gdyby obowiązywała tu reguła ostatniego administratora, operacja by
     * przeszła i test byłby czerwony. Kod odpowiedzi jest częścią asercji, ponieważ kod mówiący
     * o ostatnim administratorze znaczyłby co innego - sugerowałby, że przy drugim koncie
     * operacja by się powiodła, co jest nieprawdą.
     */
    @Test
    @DisplayName("20. Administrator nie usunie własnego konta")
    void admin_shouldNotDeleteHimself() throws Exception {
        mockMvc.perform(delete("/users/{id}", adminId)
                        .with(csrf())
                        .cookie(accessCookie(ADMIN_EMAIL)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CANNOT_DELETE_SELF"));

        assertThat(userRepository.findById(adminId)).isPresent();
    }

    /**
     * Zablokowany administrator nie jest chroniony regułą ostatniego administratora.
     *
     * Sekwencyjnie sama reguła jest nieosiągalna, bo wołający jest niezablokowanym
     * administratorem, więc przy cudzym celu niezablokowani są co najmniej dwaj. Test ustawia
     * więc stan wprost: drugi administrator zostaje zablokowany, a pierwszy go kasuje. Sprawdzany
     * jest przypadek odwrotny, bo to on dowodzi, że warunek liczy niezablokowanych, a nie
     * wszystkich z rolą administratora. Wariantu współbieżnego, w którym dwóch administratorów
     * kasuje się nawzajem, ten rodzaj testu nie odwzoruje - zamyka go blokada wierszy w bazie.
     */
    @Test
    @DisplayName("21. Zablokowanego administratora wolno usunąć - liczą się niezablokowani")
    void blockedAdmin_shouldStillBeDeletable() throws Exception {
        Cookie admin = accessCookie(ADMIN_EMAIL);

        block(admin, admin2Id, "odejście z zespołu").andExpect(status().isOk());

        mockMvc.perform(delete("/users/{id}", admin2Id).with(csrf()).cookie(admin))
                .andExpect(status().isNoContent());

        assertThat(userRepository.findById(admin2Id)).isEmpty();
        assertThat(userRepository.findById(adminId)).isPresent();
    }

    // ---------- pomocnicze do kasowania ----------

    private long unusedUserId() {
        List<Long> istniejace = List.of(adminId, admin2Id, userId);
        return istniejace.stream().mapToLong(Long::longValue).max().orElse(0) + 10_000;
    }

    private int rowsOwnedBy(String table, Long ownerId) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from " + table + " where user_id = ?", Integer.class, ownerId);
        return count == null ? 0 : count;
    }

    /**
     * Zlecenie tłumaczenia razem z plikiem w magazynie - w tej kolejności, w której robi to
     * aplikacja (najpierw obiekt, potem wiersz).
     *
     * Wiersz zakładany jest przez repozytorium, a nie przez API: przejście przez API wciągnęłoby
     * do tego testu limity znaków, walidację formatu i wykonawcę kolejki, czyli trzy rzeczy,
     * które z kasowaniem konta nie mają nic wspólnego, a potrafią uczynić go czerwonym
     * z zupełnie innego powodu.
     */
    private String seedJobWithFile(Long ownerId) {
        String key = ObjectKeys.sourceKey(
                ObjectKeys.jobPrefix(ownerId, ObjectKeys.newStorageId()), ".txt");
        objectStore.put(key, "lista".getBytes(StandardCharsets.UTF_8), "text/plain");

        TranslationJob job = new TranslationJob();
        job.setUser(userRepository.findById(ownerId).orElseThrow());
        job.setOriginalFilename("lista.txt");
        job.setTargetLang(TargetLanguage.EN_GB);
        job.setSourceObjectKey(key);
        job.setCharCount(5);
        job.setNextAttemptAt(Instant.now());
        job.setCreatedAt(Instant.now());
        translationJobRepository.save(job);

        return key;
    }
}
