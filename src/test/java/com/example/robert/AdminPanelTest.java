package com.example.robert;

import com.example.robert.user.UserRepository;
import com.example.robert.user.model.Role;
import com.example.robert.user.model.User;
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

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static com.example.robert.TestTime.sql;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Panel administracyjny: przegląd kont i cztery akcje na nich.
 *
 * Testujemy przez PEŁNY łańcuch filtrów, nie na samym serwisie, bo połowa tej zmiany jest
 * właśnie w łańcuchu: reguła autoryzacji na /users/**, sprawdzenie blokady w JwtFilter
 * i checker wpięty w DaoAuthenticationProvider. Serwis wywołany wprost potwierdziłby
 * wyłącznie, że UPDATE ustawia kolumnę.
 *
 * UWAGA METODYCZNA - CZEGO NIE ZŁAPIE JwtFilterTest: mockuje on loadUserByUsername na
 * org.springframework.security.core.userdetails.User, więc "instanceof naszej encji" jest
 * tam fałszywy i gałąź blokady w filtrze przechodzi obok testu. Test 2 poniżej jest jedynym
 * miejscem, w którym ta linia jest w ogóle sprawdzana - ta sama sytuacja co CurrentUserTest
 * kontra JwtFilterTest przy dwóch uśpionych błędach z 2026-08-04.
 *
 * Klasa świadomie NIE bierze własnego kontekstu (@TestPropertySource): każdy nowy kontekst
 * to kolejna pula Hikari trzymana do końca JVM-a, a suite wywróciła się już raz na
 * wyczerpanym max_connections PostgreSQL-a. Nic tutaj nie wymaga innej konfiguracji.
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

    private Long adminId;
    private Long admin2Id;
    private Long userId;

    /**
     * Kasujemy WSZYSTKIE konta z rolą ADMIN, nie tylko własne.
     *
     * Powód jest konkretny: reguła "ostatniego administratora" liczy niezablokowanych
     * administratorów w całej bazie, a H2 żyje dłużej niż pojedynczy kontekst (DB_CLOSE_DELAY=-1),
     * więc konto zostawione przez AdminAccessTest zmieniałoby wynik testu 10 w zależności od
     * kolejności klas. Klucze obce mają ON DELETE CASCADE, więc sesje i zlecenia znikają razem
     * z kontem.
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
     * Asercja obejmuje KOD, nie sam status. Wersja przepięta na kolumnę enabled dałaby
     * 403 ACCOUNT_NOT_CONFIRMED, a wersja polegająca wyłącznie na isAccountNonLocked()
     * dałaby 423 ACCOUNT_LOCKED - czyli komunikat "spróbuj później" przy blokadzie, która
     * sama nie mija. Sprawdzenie samego statusu przepuściłoby ten drugi wariant.
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
     * Sedno całej zmiany: blokada ma działać NATYCHMIAST. Bez sprawdzenia w JwtFilter
     * ciasteczko wydane przed blokadą otwierałoby chronione endpointy jeszcze przez
     * 15 minut ważności tokenu dostępowego.
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
     * Dziura, przez którą blokada byłaby nieskuteczna przez 7 dni: zablokowany co 15 minut
     * wymieniałby token na nowy i pracował dalej.
     *
     * Bez kontroli stanu w AuthService.refreshToken test NIE jest po prostu czerwony -
     * wraca 401 REFRESH_TOKEN_REUSED (bo blokada przez panel unieważniła tokeny), czyli
     * komunikat mówiący użytkownikowi o kradzieży tokenu. Dlatego asercja idzie po kodzie.
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
     * Ten sam wynik, ale przy ŻYWEJ sesji: blokada wpisana wprost do wiersza, tokeny
     * nietknięte. Izoluje kontrolę stanu konta od rewokacji sesji - bez niej test 3
     * przechodziłby na samym unieważnieniu tokenów, a kontrola stanu mogłaby nie istnieć.
     * Odwzorowuje też wyścig: sesja założona po nałożeniu blokady, a przed rewokacją.
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
     * Blokada ma zrywać sesje, a nie tylko zamykać drzwi na przyszłość. Testy 3 i 4
     * przeszłyby na samej kontroli stanu, więc bez tego wypadnięcie revokeAllSessions
     * z AdminUserService przeszłoby niezauważone - do momentu, w którym ktoś zdejmie
     * blokadę i odkryje, że stara sesja ożyła.
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
     * PADA W CHWILI, GDY KTOŚ "UPROŚCI" I WRÓCI DO locked_until. Zdjęcie blokady po
     * nieudanych logowaniach zeruje failed_login_attempts i locked_until - gdyby kara
     * administracyjna siedziała w tej samej kolumnie, ta akcja by ją zdejmowała. Tak samo
     * zdejmowałby ją każdy udany login i każdy reset hasła, czyli działanie SAMEGO UKARANEGO.
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
     * KONTROLA NEGATYWNA - najważniejszy test w tej klasie.
     *
     * Pada dokładnie wtedy, gdy JwtFilter zacznie sprawdzać isAccountNonLocked() zamiast
     * isBlocked(). Blokadę po nieudanych logowaniach wywołuje KAŻDY, kto zna czyjś adres
     * i wpisze trzy razy złe hasło - gdyby zrywała żywe sesje, byłoby to gotowe narzędzie
     * do wybijania dowolnego zalogowanego użytkownika z aplikacji na 15 minut.
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
     * WYMAGA DRUGIEGO KONTA ADMIN - i to nie jako dekoracji: dopiero zablokowanie go
     * sprawia, że kolejna próba dotyczy OSTATNIEGO niezablokowanego administratora.
     * Przy jednym koncie test przeszedłby na regule "nie blokuj siebie" i nie
     * dyskryminowałby niczego.
     *
     * Kolejność kontroli w AdminUserService jest tym, co ten test przypina: reguła
     * ostatniego administratora stoi PRZED regułą blokady siebie, bo nazywa prawdziwy
     * skutek (nie ma drogi powrotnej do roli ADMIN - AdminBootstrap nie promuje istniejących
     * kont USER, więc wyjściem byłby ręczny UPDATE w bazie).
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
     * Regresja na ŚCIEŻKĘ kontrolera. Reguła .requestMatchers("/users/**").hasRole("ADMIN")
     * istnieje w SecurityConfig od usunięcia dawnego UserController - zmapowanie panelu
     * gdziekolwiek indziej (np. /admin/users) wpuściłoby pod anyRequest().authenticated()
     * każdego zalogowanego użytkownika.
     *
     * PIERWSZA ASERCJA JEST TU KONIECZNA i nie jest powtórzeniem innych testów: samo "zwykły
     * dostaje 403 na /users" byłoby zielone także wtedy, gdyby pod /users nie było ŻADNEGO
     * kontrolera - nieznana ścieżka pod tą regułą też kończy się odmową. Dopiero 200 dla
     * administratora dowodzi, że panel faktycznie stoi tam, gdzie sięga reguła.
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
    }

    // ---------- 12-15: lista i wyszukiwanie ----------

    /**
     * Wiersz z wielkimi literami w adresie zapisujemy wprost przez repozytorium, bo
     * EmailNormalizer działa w konstruktorach DTO - takie konta pochodzą sprzed jego
     * wprowadzenia. Bez lower() po stronie kolumny PostgreSQL (i H2 w MODE=PostgreSQL)
     * ich nie znajdzie, a administrator zobaczy pustą listę zamiast konta, o które pytał.
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
     * Asercja po SUROWEJ treści odpowiedzi, nie po polach DTO: gdyby ktoś zwrócił stąd
     * encję User zamiast projekcji, test na polach dalej byłby zielony, a hash hasła
     * pojechałby do przeglądarki. Ta sama metoda co w CurrentUserTest.
     */
    @Test
    @DisplayName("14. Lista nie wynosi hasha hasła ani wewnętrznych pól UserDetails")
    void list_shouldNotExposePasswordHash() throws Exception {
        // Z filtrem, a nie cała lista: kolejność jest po id rosnąco, a konta tej klasy
        // powstają jako ostatnie, więc na pierwszej stronie mogłoby ich nie być.
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
     * Powtórna blokada nie nadpisuje ŚLADU AUDYTOWEGO. Bez warunku "and blockedAt is null"
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
        List<Long> istniejace = List.of(adminId, admin2Id, userId);
        long nieistniejace = istniejace.stream().mapToLong(Long::longValue).max().orElse(0) + 10_000;

        action(accessCookie(ADMIN_EMAIL), "unblock", nieistniejace)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
    }
}
