# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project overview

Despite the Maven artifact name (`file_translator`) and root package (`com.example.robert`), this is
currently a Spring Boot 4 (Java 21) JWT-based authentication/user-management REST API — the file
translation feature is planned, not implemented. There is no frontend in this repo; `app.frontend.url`
only points at an external SPA (default `http://localhost:5173`) used for CORS and for building email
confirmation links.

The root package `com.example.robert` and artifact id `file_translator` are leftovers from the original
scaffold. Renaming them is a pending cosmetic task — do not rename opportunistically while doing
unrelated work.

## Build, test, run

Use the Maven wrapper (`./mvnw` on bash, `./mvnw.cmd` on plain Windows shells):

```
./mvnw compile                     # compile
./mvnw test                        # run all tests
./mvnw verify                      # tests + JaCoCo coverage report (target/site/jacoco)
./mvnw test -Dtest=AuthServiceTest # run a single test class
./mvnw test -Dtest=AuthServiceTest#login_shouldReturnTokenPair  # run a single test method
./mvnw spring-boot:run             # run the app (defaults to the "dev" profile)
docker compose up -d               # full local stack: MySQL + Mailpit + Redis + app
```

Tests run against H2 in-memory (`src/test/resources/application-test.yml`, MySQL compatibility mode),
so no live database is required. Running the app itself (`dev`/`prod`) requires a real MySQL instance.

Surefire is configured with `@{surefireArgLine}` (JaCoCo agent — late-evaluation syntax is required,
`${...}` would resolve to the empty default before JaCoCo sets it), `-javaagent:mockito-core`, and
`-Dfile.encoding=UTF-8` — the last one matters because comments and log messages are in Polish.

## Spring Boot 4 specifics that have already cost time

- **Auto-configurations were split out of `spring-boot-autoconfigure`** into per-technology modules.
  Adding a library is not enough: e.g. `liquibase-core` alone runs no migrations, `spring-boot-liquibase`
  is also required. Same pattern for MockMvc (`spring-boot-starter-webmvc-test`).
- **Jackson 3 (`tools.jackson`) is the auto-configured mapper**, not Jackson 2. `com.fasterxml.jackson`
  is still on the classpath transitively (jjwt, springdoc) but there is no bean of that type.

## Configuration and profiles

- `application.yml` is the base config; `spring.profiles.active` defaults to `dev`.
- `application-dev.yml`: local MySQL, Mailpit/Mailtrap SMTP, cookies with `secure: false` (localhost is
  plain HTTP, so a Secure cookie would be silently dropped by the browser).
- `application-prod.yml`: all secrets from env vars; Swagger UI disabled; `forward-headers-strategy`.
- `application-test.yml`: H2 + Liquibase + `ddl-auto: validate`; rate limiting disabled by default
  (buckets are stateful and would leak between tests), account lock threshold lowered to 3.
- `JWT_SECRET` must be base64-encoded and long enough for HMAC signing (see `JwtUtil.getKey()`).
- Schema changes go through Liquibase changelogs in `src/main/resources/db/changelog/changes/`,
  registered in `db.changelog-master.xml`. **Add new changesets as new files** — never edit a changeset
  that has already run anywhere. Engine-specific column types come from changelog properties
  (`${type.datetime}`, `${type.boolean}`) so the same changelog fits MySQL and H2.
- Hibernate runs with `ddl-auto: validate` everywhere. Liquibase owns the schema; Hibernate only checks
  that entities match it and fails startup if they drifted.

## Package layout (package-by-feature)

```
com.example.robert
├── auth/            login, sessions, refresh-token rotation, lockout, registration, verification
│   ├── dto/  model/  repository/
├── user/            user account, CRUD, User entity (auth depends on user, never the reverse)
│   ├── dto/  model/
├── notification/    transactional email
└── common/          shared infrastructure only — no domain logic
    ├── config/  security/  security/ratelimit/  web/  exception/  observability/  validation/
```

Do not reintroduce layer packages (`controllers/`, `services/`, `repositories/`) at the root.
`common/` growing domain logic is a signal that a new feature package is needed.

Interfaces with a single implementation were deliberately removed (`*ServiceInterface`). Add an
interface only where a second implementation genuinely exists — `BucketProvider` is the example.

## Architecture

**Request flow for authenticated endpoints:** `TraceIdFilter` (correlation id into MDC and the
`X-Request-Id` header) → `RateLimitFilter` (runs before the Spring Security chain, so rejected requests
cost neither a DB query nor a BCrypt comparison) → security chain → `JwtFilter` (reads the JWT from the
`accessToken` cookie, never from an `Authorization` header) → controller. `SecurityConfig` is stateless
and authorizes by path: `/auth/**` and health/docs are public, `/users/**` and the remaining actuator
endpoints require `ROLE_ADMIN`.

`JwtFilter` is registered as an explicit `@Bean`, not `@Component` — a `@Component` filter would also be
auto-registered in the servlet container and run outside the security chain.

**AuthenticationManager is built explicitly** (`ProviderManager` + `DaoAuthenticationProvider` +
`DefaultAuthenticationEventPublisher`) rather than taken from `AuthenticationConfiguration`. The
auto-assembled one had no event publisher, so no authentication events were published and the failed
login counter never incremented.

**Tokens are cookie-based.** `CookieService` issues `accessToken` (15 min, path `/`) and `refreshToken`
(7 days, path `/auth` — narrow, but wide enough to reach `/auth/logout`) as `httpOnly` cookies;
`secure`/`sameSite`/max-age come from `CookieProperties`. Access and refresh JWTs are distinguished by a
`type` claim. Every token carries a random `jti` — without it two tokens issued to the same user within
one second are byte-identical, because `iat` has second precision.

**Sessions are stateful on the server side.** Every refresh token has a `refresh_tokens` row storing only
its SHA-256 (`TokenHasher`). Refreshing rotates: the presented token is revoked and a new one is issued
in the same `family_id`. Presenting an already-revoked token means a copy exists, so the whole family is
revoked (`RefreshTokenService`). Logout revokes the family.

**Transaction propagation matters in two places, and both were bugs before:** the failed-login counter
(`LoginAttemptService.recordFailure`) and the family revocation on reuse detection both run in
`REQUIRES_NEW`, because they are triggered inside an operation that then throws and rolls back. In
`RefreshTokenService` this uses a `TransactionTemplate` rather than the annotation, since self-invocation
bypasses the Spring proxy. Counters and audit writes must survive the rollback of the operation they
describe.

**Registration is a two-phase, transactional-outbox-style flow:** `AuthService.register` saves the user
with `enabled=false` and stores only the SHA-256 of a random UUID in `VerificationToken` (24h), then
publishes `UserRegisteredEvent`. `UserRegisteredEventListener` is `@TransactionalEventListener(AFTER_COMMIT)`,
so a rolled-back registration never sends mail. `EmailService.sendVerificationEmail` is `@Async`
(`AsyncConfig`, thread prefix `async-mail-`) and catches both `MessagingException` and `MailException` —
the latter is what `mailSender.send()` actually throws when SMTP is unreachable.
`ExpiredTokenCleanupJob` purges expired verification and refresh tokens nightly.

**Error handling is centralized and uses RFC 9457 `ProblemDetail`.** `GlobalExceptionHandler` extends
`ResponseEntityExceptionHandler`, so Spring MVC's own exceptions also return `application/problem+json`.
Every response carries `code` (stable machine-readable identifier — frontends branch on this, not on the
Polish `detail` text), `timestamp` and `traceId`. Build them via `ApiProblem.of(...)`, never by hand.
Places the advice cannot reach — filters and Spring Security entry points — use `ProblemResponseWriter`
so the shape stays identical.

**DTO/entity boundary:** MapStruct (`UserMapper`, `componentModel = "spring"`). `id`, `role`, `enabled`
and `authorities` are excluded from `toEntity` — they are set by service code, never trusted from input.

**`User` doubles as a Spring Security `UserDetails`.** `isAccountNonLocked()` is backed by `lockedUntil`,
so the lock expires on its own and is checked before the password comparison. Splitting the principal out
of the entity is a pending task, needed before OAuth2 login (the principal becomes an `OidcUser` there).

**Bean validation belongs in DTOs, not entities.** The entity carries only column constraints. Password
policy on `User` was actively harmful — it validated the BCrypt hash, so any password passed.

## Conventions to preserve

- Comments and log messages are in Polish (the author's working language) — match this rather than
  switching to English mid-file. Comments explain *why*, not *what*.
- Every source file in `src/main` carries a copyright header comment; keep it.
- Never log email addresses or other personal data — log `id` and rely on `traceId` for correlation.
- JWT failures carry a machine-readable code on `JwtAuthenticationException.tokenError`
  (`EXPIRED_TOKEN`, `INVALID_TOKEN`, `INVALID_TOKEN_TYPE`, `VALIDATION_ERROR`, `REFRESH_TOKEN_UNKNOWN`,
  `REFRESH_TOKEN_REUSED`, `REFRESH_TOKEN_MISSING`) — extend this set rather than inventing new
  exception types.
- **Every** error response must be a `ProblemDetail` with a `code`. Never return an empty
  `ResponseEntity.status(...).build()` — a frontend calling `response.json()` breaks on an empty body.
- Do not let error responses leak whether an email address is registered. Bad credentials, unknown user
  and validation failures must stay indistinguishable from the outside.
