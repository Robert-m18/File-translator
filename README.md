# File Translator API

Backend REST API w Spring Boot 4 / Java 21. Docelowo usługa tłumaczenia plików;
obecnie zaimplementowany jest kompletny moduł uwierzytelniania — rejestracja z potwierdzeniem
adresu, logowanie na ciasteczkach, rotacja sesji, blokada konta i reset hasła. Panelu
administracyjnego ani zarządzania użytkownikami nie ma (patrz sekcja Endpointy).

> Nazwa artefaktu (`file_translator`) i pakiet (`com.example.robert`) pochodzą z pierwotnego
> szkieletu projektu — ich uporządkowanie jest zaplanowane w etapie refaktoryzacji struktury.

---

## Stack

| Warstwa | Technologia |
|---|---|
| Runtime | Java 21, Spring Boot 4.0.6 |
| Bezpieczeństwo | Spring Security 7, JWT (jjwt), BCrypt |
| Persystencja | Spring Data JPA / Hibernate, PostgreSQL 17 |
| Migracje | Liquibase (XML) |
| Mapowanie DTO | ręczne — MapStruct usunięty, obsługiwał jeden mapper do nieistniejącego już CRUD-u |
| Dokumentacja | springdoc-openapi 3 (OpenAPI 3 + Swagger UI) |
| Obserwowalność | Spring Boot Actuator, Micrometer + Prometheus |
| Ochrona przed nadużyciami | bucket4j (token bucket), Caffeine lub Redis |
| Poczta | Spring Mail + Thymeleaf, transakcyjna skrzynka nadawcza (outbox) |
| Testy | JUnit 5, Mockito, MockMvc, H2, JaCoCo |

---

## Szybki start

### Docker Compose (zalecane)

Podnosi PostgreSQL, lokalny serwer SMTP i aplikację:

```bash
docker compose up -d
```

| Co | Gdzie |
|---|---|
| API | http://localhost:2009 |
| Swagger UI | http://localhost:2009/swagger-ui.html |
| Health check | http://localhost:2009/actuator/health |
| Skrzynka pocztowa (Mailpit) | http://localhost:8025 |
| Baza (DBeaver/psql) | `localhost:5433`, baza `userapitest`, `postgres`/`postgres` |

Maile weryfikacyjne nie wychodzą na zewnątrz — lądują w Mailpicie pod adresem powyżej.

**Port bazy to 5433, nie 5432.** Domyślny port zostawiamy wolny, bo na maszynach
deweloperskich stoi tam zwykle lokalnie zainstalowany PostgreSQL — zajęcie go albo wywala
`docker compose up`, albo cicho podłącza narzędzia do cudzego serwera.

### Uruchomienie lokalne

Wymaga bazy z Compose'a (`docker compose up -d postgres`). Konfiguracja domyślna celuje
w `localhost:5433` i w Mailpita na `localhost:1025`, więc nie trzeba ustawiać niczego:

```bash
./mvnw spring-boot:run
```

Schemat bazy tworzy Liquibase przy starcie. Hibernate działa w trybie `validate`
i **nie** modyfikuje schematu.

### Testy

```bash
./mvnw test                    # wszystkie testy
./mvnw verify                  # testy + raport pokrycia (target/site/jacoco/index.html)
./mvnw test -Dtest=AuthServiceTest
./mvnw test -Dtest=AuthServiceTest#login_shouldReturnTokenPair
```

Testy działają na H2 w pamięci (tryb zgodności `MODE=PostgreSQL`) — działająca baza nie
jest potrzebna. Schemat budują te same migracje Liquibase co na produkcji, więc `mvn test`
wykrywa rozjazd encji z migracjami. Job `integration` w CI powtarza cały zestaw na
prawdziwym PostgreSQL-u i Redisie — tryb zgodności H2 nie odwzorowuje wiernie ani składni
`FOR NO KEY UPDATE ... SKIP LOCKED`, ani zachowania migracji.

---

## Architektura

### Organizacja pakietów — decyzja i uzasadnienie

Kod jest podzielony **według funkcjonalności** (package-by-feature), a nie według warstw
technicznych (`controllers/`, `services/`, `repositories/`):

```
com.example.robert
├── auth/            logowanie, sesje, rotacja tokenów, blokada konta, rejestracja
│   ├── dto/  model/  repository/
├── user/            konto użytkownika, CRUD, encja User
│   ├── dto/  model/
├── notification/    wysyłka maili transakcyjnych
└── common/          wspólna infrastruktura
    ├── config/         konfiguracja Springa (bezpieczeństwo, OpenAPI, async)
    ├── security/       JWT, ciasteczka, limity żądań, punkty wejścia Spring Security
    │   └── ratelimit/  wymienny magazyn limitów (pamięć / Redis)
    ├── web/            format błędów (RFC 9457), odpowiedzi sukcesu
    ├── exception/      wyjątki dziedzinowe
    ├── observability/  korelacja żądań
    └── validation/     własne adnotacje walidacyjne
```

**Dlaczego tak:**

* **Zmiany chodzą wzdłuż funkcjonalności, nie wzdłuż warstw.** Dodanie logowania przez Google
  dotyka kontrolera, serwisu, encji i repozytorium — czyli w układzie warstwowym czterech
  różnych katalogów naraz. Tutaj to praca w jednym.
* **Granice są widoczne w kompilatorze.** Zależność `auth → user` jest jednokierunkowa i widać
  ją w importach. W układzie warstwowym wszystko leży w `services/` i nic nie sygnalizuje,
  że coś sięga tam, gdzie nie powinno.
* **Skalowanie projektu.** Docelowy moduł tłumaczenia plików to kilkanaście klas. W układzie
  warstwowym rozsypałyby się po tych samych czterech katalogach, wymieszane z uwierzytelnianiem.
  Tutaj to osobny pakiet, który nie dotyka `auth/` w ogóle.
* **`common/` jest świadomie wąskie.** Trafia tam wyłącznie infrastruktura bez wiedzy
  o dziedzinie. Gdyby zaczęło puchnąć o logikę biznesową, byłby to sygnał, że powstaje
  nowa funkcjonalność, a nie kolejny worek na wszystko.

**Czego tu celowo nie ma:** architektury heksagonalnej ani podziału na moduły Mavena.
Przy jednym module i takiej wielkości projektu byłby to koszt bez zwrotu — port i adapter
dla każdego repozytorium dokładają warstwę pośrednią, nie usuwając żadnego realnego problemu.
Package-by-feature to naturalny krok przed tym podziałem i wystarczający punkt zatrzymania.

Konsekwencją tej zmiany było też usunięcie interfejsów `*ServiceInterface` z jedną
implementacją. Interfejs ma sens, gdy istnieje druga implementacja albo gdy trzeba odwrócić
kierunek zależności — inaczej dokłada plik do utrzymania i nic nie wnosi (Mockito i Spring
radzą sobie z klasami konkretnymi). Interfejsy zostały tam, gdzie realnie istnieje wybór
implementacji, jak `BucketProvider`.

### Przepływ uwierzytelnionego żądania

```
Request
  → TraceIdFilter          (nadaje X-Request-Id, wkłada traceId do MDC)
  → CORS / SecurityFilterChain
  → JwtFilter              (czyta ciasteczko accessToken, waliduje, ustawia SecurityContext)
  → AuthorizationFilter     (reguły dostępu wg ścieżki)
  → Controller → Service → Repository
```

Błędy:

* wewnątrz kontrolerów i serwisów → `GlobalExceptionHandler` (`@RestControllerAdvice`)
* wewnątrz filtrów → `ProblemResponseWriter` (advice tam nie sięga — filtry
  działają przed `DispatcherServlet`)
* brak uwierzytelnienia / brak uprawnień → `RestAuthenticationEntryPoint` / `RestAccessDeniedHandler`

Wszystkie trzy ścieżki zwracają ten sam format.

### Tokeny w ciasteczkach, nie w nagłówku

`AuthController` nigdy nie zwraca tokenu w ciele odpowiedzi. `CookieService` wystawia:

| Ciasteczko | Ważność | Ścieżka | Atrybuty |
|---|---|---|---|
| `accessToken` | 15 min | `/` | httpOnly, secure\*, SameSite\* |
| `refreshToken` | 7 dni | `/auth` | httpOnly, secure\*, SameSite\* |

\* z konfiguracji (`app.cookie.*`) — na dev `secure: false`, bo localhost działa po HTTP.

`httpOnly` sprawia, że JavaScript nie odczyta tokenu, co odcina najprostszą ścieżkę
kradzieży sesji przez XSS. Ścieżka `refreshToken` jest zawężona, więc token odświeżający
nie jest wysyłany przy każdym żądaniu do API.

Access i refresh rozróżnia claim `type` (`access` / `refresh`), sprawdzany w `AuthService.refreshToken`.
Każdy token ma losowy `jti`, więc dwa tokeny wydane w tej samej sekundzie nigdy nie są identyczne.

### Sesje: rotacja tokenów z wykrywaniem kradzieży

Access token pozostaje bezstanowy (15 min), ale każdy token odświeżający ma odpowiadający
mu rekord w tabeli `refresh_tokens` — dopiero to pozwala unieważnić sesję przed jej wygaśnięciem.

* **Rotacja** — każde `/auth/refresh` zużywa przedstawiony token i wydaje nowy. Token działa raz.
* **Rodzina** — wszystkie tokeny z kolejnych rotacji jednej sesji dzielą `family_id`.
* **Wykrycie ponownego użycia** — próba użycia tokenu już zużytego oznacza, że istnieje jego
  kopia. Nie da się rozstrzygnąć, kto jest prawowitym właścicielem, więc unieważniana jest
  **cała rodzina** i obie strony muszą zalogować się ponownie.
* **Wylogowanie** — unieważnia rodzinę, czyli sesję danego urządzenia. Sesje na innych
  urządzeniach zostają nietknięte.

W bazie leży wyłącznie SHA-256 tokenu (`TokenHasher`) — wyciek dumpu nie daje działających sesji.

### Ochrona przed atakiem siłowym — dwie niezależne warstwy

| Mechanizm | Chroni | Klucz | Reakcja |
|---|---|---|---|
| Limit żądań (`RateLimitFilter`, bucket4j) | serwer i pozostałych użytkowników | adres IP + ścieżka | 429 + `Retry-After` |
| Blokada konta (`LoginAttemptService`) | konkretne konto | adres email | 423 po 5 nieudanych próbach, na 15 min |

Rozdzielenie jest celowe: limit per IP nie chroni konta przed atakiem rozproszonym,
a blokada konta nie chroni serwera przed zalewem żądań na tysiąc różnych kont.
Filtr limitujący działa **przed** łańcuchem Spring Security, więc odrzucone żądanie
nie kosztuje ani zapytania do bazy, ani porównania hasha BCrypt.

**Magazyn limitów jest wymienny** (`app.rate-limit.store`):

| Wartość | Zachowanie | Kiedy |
|---|---|---|
| `memory` (domyślnie) | kubełki w pamięci procesu (Caffeine) | jedna instancja aplikacji |
| `redis` | kubełki w Redisie, operacje compare-and-swap | wiele instancji za load balancerem |

Wybór robi `BucketProvider` — `RateLimitFilter` nie wie, która implementacja jest wstrzyknięta.

Powód istnienia obu: przy trzech instancjach z magazynem w pamięci każda liczy własne kubełki,
więc limit „10 na minutę" staje się faktycznie 30 na minutę. Redis to naprawia, ale dokłada
zależność sieciową na ścieżce **każdego** żądania do `/auth/**` i kolejny punkt awarii —
dlatego nie jest wartością domyślną. Redis nie jest też wymagany do startu aplikacji,
dopóki `store` ma wartość `memory`.

### Rejestracja — dwuetapowa, oparta o zdarzenia

1. `AuthService.register` — zapis użytkownika z `enabled=false`, wygenerowanie losowego
   UUID i zapis wyłącznie jego **hasha SHA-256** (ważność 24 h), publikacja `UserRegisteredEvent`.
2. `UserRegisteredEventListener` — `@TransactionalEventListener(phase = AFTER_COMMIT)`:
   mail poleci wyłącznie wtedy, gdy transakcja rejestracji faktycznie się zatwierdziła.
3. `EmailService.sendVerificationEmail` — `@Async` (osobna pula, prefiks `async-mail-`),
   szablon Thymeleaf, link do `/confirm-email?token=...` na froncie.
4. `AuthService.confirmEmail` — wyszukanie po hashu, sprawdzenie ważności,
   `enabled = true` przez dirty checking, usunięcie zużytego tokenu.
5. `ExpiredTokenCleanupJob` — `@Scheduled` (co noc o 3:00), czyszczenie wygasłych tokenów.

W bazie nie ma surowego tokenu — wyciek dumpu bazy nie pozwala aktywować cudzego konta.

### Format błędów — RFC 9457

Każdy błąd to `application/problem+json`:

```json
{
  "type": "https://filetranslator.dev/problems/validation-failed",
  "title": "Błąd walidacji",
  "status": 400,
  "detail": "Żądanie zawiera nieprawidłowe dane",
  "code": "VALIDATION_FAILED",
  "timestamp": "2026-07-25T10:15:30.123Z",
  "traceId": "a1b2c3d4e5f60718",
  "errors": [
    { "field": "password", "message": "Hasło musi mieć od 8 do 72 znaków" }
  ]
}
```

* `code` — stabilny kod maszynowy; frontend reaguje na niego, a nie na tekst komunikatu
* `traceId` — spina odpowiedź z logami serwera (ten sam identyfikator wraca w nagłówku `X-Request-Id`)

---

## Endpointy

| Metoda | Ścieżka | Dostęp | Opis |
|---|---|---|---|
| GET | `/auth/csrf` | publiczny | Token CSRF w **ciele** odpowiedzi (ciasteczko jest `httpOnly`) |
| POST | `/auth/register` | publiczny | Rejestracja — zawsze `202`, nigdy nie zdradza, czy adres jest zajęty |
| POST | `/auth/confirm` | publiczny | Potwierdzenie adresu i utworzenie konta |
| POST | `/auth/login` | publiczny | Logowanie, ustawia ciasteczka `accessToken` i `refreshToken` |
| GET | `/auth/me` | **zalogowany** | Dane bieżącego użytkownika — jedyne źródło prawdy o stanie sesji |
| POST | `/auth/refresh` | refresh token | Rotacja tokenów z wykrywaniem ponownego użycia |
| POST | `/auth/logout` | publiczny | Unieważnia rodzinę tokenów i czyści ciasteczka |
| POST | `/auth/forgot-password` | publiczny | Wysyła link resetu — identyczna odpowiedź dla adresu znanego i nieznanego |
| POST | `/auth/reset-password` | token z maila | Ustawia nowe hasło, unieważnia wszystkie sesje |
| GET | `/actuator/health`, `/actuator/info` | publiczny | Health check i probe'y dla orkiestratora |
| GET | `/actuator/metrics`, `/actuator/prometheus` | `ROLE_ADMIN` | Metryki |

Pełna specyfikacja: `/swagger-ui.html` (wyłączone na profilu `prod`).

**`/users/**` nie istnieje.** `UserController` został usunięty razem z CRUD-em, którego nikt nie
wywoływał. Reguła `/users/** → ROLE_ADMIN` została w `SecurityConfig` celowo: domyślna odmowa
jest właściwym stanem wyjściowym, więc gdy kontroler wróci, jego endpointy będą chronione od
pierwszego commitu, a nie dopiero po tym, jak ktoś zauważy, że są otwarte.

### Dostęp administracyjny

Endpointy z kolumną `ROLE_ADMIN` wymagają konta z tą rolą, a rejestracja zakłada wyłącznie
konta `USER`. Jedyną drogą do roli administratora jest konto zakładane przy starcie aplikacji
(`AdminBootstrap`), włączane trzema zmiennymi środowiskowymi:

```
ADMIN_ENABLED=true
ADMIN_EMAIL=admin@twoja-domena
ADMIN_PASSWORD=<hasło zgodne z polityką rejestracji>
```

W `docker-compose.yml` są już ustawione, więc lokalnie konto powstaje samo. Zachowanie:

- konto powstaje raz, od razu włączone (`enabled = true`) — potwierdzenie adresem odpada;
- **hasło istniejącego konta nie jest nadpisywane** przy kolejnych startach;
- **istniejące konto z rolą `USER` nie jest podnoszone do `ADMIN`** — pomyłka w adresie nie
  może oddać cudzego konta. Aplikacja loguje wtedy WARN i nie robi nic;
- hasło niespełniające polityki rejestracji **zatrzymuje start**, zamiast zakładać słabe konto.

Hasło administratora resetuje się tak samo jak każde inne, przez `/auth/forgot-password`.

**Metryki są osiągalne dla człowieka w przeglądarce, nie dla scrape'a.** Prometheus nie zaloguje
się ciasteczkiem, więc `GET /actuator/prometheus` bez sesji zwróci mu 401 — to stan oczekiwany,
uzasadnienie i warunki zmiany w `CLAUDE.md`, sekcja *Accepted trade-offs*.

---

## Konfiguracja

| Profil | Baza | Sekrety | Swagger |
|---|---|---|---|
| `dev` (domyślny) | PostgreSQL z Compose'a (`localhost:5433`) | wartości domyślne w pliku | włączony |
| `prod` | z `DATABASE_*` | wyłącznie ze zmiennych środowiskowych | wyłączony |
| `test` | H2 w pamięci | w pliku testowym | — |

Zmienne środowiskowe dla `prod`: `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD`,
`JWT_SECRET`, `FRONTEND_URL`, `MAIL_HOST`, `MAIL_USERNAME`, `MAIL_PASSWORD`.

`JWT_SECRET` musi być kluczem zakodowanym w base64, o długości wystarczającej dla HMAC-SHA
(min. 256 bitów) — patrz `JwtUtil.getKey()`.

### Migracje bazy

Changelogi w `src/main/resources/db/changelog/`, rejestrowane w `db.changelog-master.xml`.
Nowe zmiany dodajemy jako **nowe pliki** w `changes/` — nigdy nie edytujemy changesetu,
który został już gdziekolwiek wykonany.

---

## Konwencje

* Komentarze i logi po polsku — to język roboczy autora; przy edycji plików trzymamy się tej konwencji.
* Każdy plik w `src/main` ma nagłówek z informacją o prawach autorskich.
* Wyjątki JWT niosą maszynowy kod (`EXPIRED_TOKEN`, `INVALID_TOKEN`, `INVALID_TOKEN_TYPE`) —
  rozszerzamy ten zbiór zamiast tworzyć nowe typy wyjątków.
* Adresy email nie trafiają do logów (dane osobowe) — do korelacji służy `traceId`.

---

## Roadmapa

| Etap | Zakres | Status |
|---|---|---|
| 0 | Naprawa fundamentów (Liquibase, ciasteczka, walidacja) | ✅ |
| 1 | Higiena projektu (Docker, CI, OpenAPI, Actuator, ProblemDetail) | ✅ |
| 2 | Refaktoryzacja struktury (package-by-feature) | ✅ |
| 2b | Audyt encji (`createdAt`/`updatedAt`), przejście na `Instant` | ⏳ |
| 3 | Twardnienie auth (rotacja refresh tokenów, rate limiting, lockout) | ✅ |
| 3b | Reset hasła, skrzynka nadawcza maili, `GET /auth/me`, konto administratora | ✅ |
| 3c | Migracja bazy na PostgreSQL 17 | ✅ |
| 4 | Rozszerzenie modelu użytkownika (dostawcy tożsamości) | ⏳ |
| 5 | Logowanie kodem jednorazowym / magic link | ⏳ |
| 6 | Logowanie przez Google (OAuth2) | ⏳ |
| 7 | Testy integracyjne na Testcontainers | ⏳ |
| 8 | Translator plików — MVP (upload, kolejka zadań, tłumaczenie, pobranie) | ⏳ |
| 9 | Translator v2 (PDF/XLSX, SSE, limity, cache, metryki) | ⏳ |
