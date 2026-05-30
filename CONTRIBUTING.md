# Contributing to AtlasFlag

Thank you for taking the time to contribute. This document covers everything you need to get a development environment running, understand the codebase, and submit a good pull request.

---

## Table of contents

- [Getting started](#getting-started)
- [Project structure](#project-structure)
- [Development workflow](#development-workflow)
- [Code conventions](#code-conventions)
- [Testing](#testing)
- [Submitting a pull request](#submitting-a-pull-request)
- [Architecture invariants](#architecture-invariants)
- [Reporting bugs and security issues](#reporting-bugs-and-security-issues)

---

## Getting started

### Prerequisites

| Tool | Version | Notes |
|---|---|---|
| Java | 21+ | [Adoptium](https://adoptium.net/) — set `JAVA_HOME` |
| Docker | Any recent | For the local PostgreSQL container |
| Git | Any | Standard |

Run `java -version` to confirm. See [SETUP.md](SETUP.md) if you need help configuring Java.

### Fork and clone

```bash
git clone https://github.com/<your-username>/AtlasFlag.git
cd AtlasFlag
```

### Start the database

```bash
cd infra && docker-compose up -d && cd ..
```

This starts PostgreSQL on port 5432 with the credentials in `infra/docker-compose.yml`.

### Run the service

```bash
./gradlew :atlas-flag-service:bootRun
```

Wait for `Started AtlasFlagApplication` in the logs (~10 seconds), then open [http://localhost:8080](http://localhost:8080). Default login: `admin` / `admin123`.

---

## Project structure

```
AtlasFlag/
├── service/                         # Spring Boot backend (the main module)
│   └── src/main/java/com/atlasflag/
│       ├── config/                  # Spring configuration beans
│       ├── controller/              # REST controllers + Thymeleaf view controller
│       ├── domain/                  # JPA entities
│       ├── dto/                     # Request/response shapes
│       ├── repository/              # Spring Data repositories
│       ├── security/                # JWT filter + token provider
│       └── service/                 # Business logic (start here)
│   └── src/main/resources/
│       ├── templates/               # Thymeleaf HTML (dashboard, login)
│       ├── static/js/app.js         # All frontend JS
│       └── db/migration/            # Flyway SQL migrations (V1–V3 applied, add V4+)
├── sdk-java/                        # Java client SDK
├── starter-spring-boot/             # Spring Boot auto-configuration starter
├── frontend/                        # Static copy of templates/ for Vercel deployment
│   └── js/app.js                    # Kept in sync with static/js/app.js
└── infra/
    └── docker-compose.yml           # Local PostgreSQL
```

**Where to start:** `service/src/main/java/com/atlasflag/service/FeatureFlagService.java` is the core. Controllers are thin — they delegate to services, which own all business logic.

---

## Development workflow

### Making backend changes

1. Edit Java source under `service/src/main/java/`
2. Spring Boot DevTools is on the classpath — most changes hot-reload without a restart
3. For changes to `application.yml` or new beans, restart the service

### Making frontend changes

The dashboard is a single Thymeleaf template + one JS file:

- **HTML:** `service/src/main/resources/templates/dashboard.html`
- **JS:** `service/src/main/resources/static/js/app.js`

After editing, refresh the browser — no build step needed. The service serves these files directly; there is no separate frontend build or deployment step.

### Adding a database migration

Never modify the existing migration files (V1–V3). Create a new file:

```
service/src/main/resources/db/migration/V4__describe_your_change.sql
```

Flyway runs migrations in version order on startup. Test locally before submitting.

---

## Code conventions

These follow the rules in [CLAUDE.md](CLAUDE.md). The short version:

**Java**
- Constructor injection only — no `@Autowired` on fields
- Controllers return `ResponseEntity` — no business logic in controllers
- Service methods that write to `feature_flags` must call `auditService.logAction(...)` and use `@CacheEvict`/`@CachePut` — see [Architecture invariants](#architecture-invariants)
- Error responses use `Map.of("message", "...")` — consistent with `AuthController`
- Avoid `@Transactional` on `@Async` methods

**Frontend (app.js)**
- All server data rendered via `innerHTML` must go through `escapeHtml()` — no exceptions
- No direct DOM manipulation outside the explicit render functions (`renderFlags`, `renderAuditLogs`, etc.)
- New UI state goes through the existing tab/modal pattern — do not add new global state variables without discussion

**General**
- No comments explaining what the code does — code should be self-documenting
- Only add a comment when the WHY is non-obvious (hidden constraint, workaround, invariant)
- Keep functions small with one clear responsibility
- Prefer explicit over clever

---

## Testing

### Run all tests

```bash
./gradlew test
```

Docker must be running — service tests use Testcontainers to spin up a real PostgreSQL instance.

### Run tests by module

```bash
# Service (requires Docker)
./gradlew :atlas-flag-service:test

# SDK unit tests (no Docker needed)
./gradlew :atlas-flag-sdk-java:test

# Starter tests
./gradlew :atlas-flag-spring-boot-starter:test
```

### What to test

- New service methods → unit test with mocked dependencies
- New endpoints → integration test with Testcontainers (follow existing test patterns)
- New SDK features → unit test in `sdk-java/src/test/`
- Bug fixes → add a test that fails before the fix and passes after

---

## Submitting a pull request

1. **Create a branch** from `main`:
   ```bash
   git checkout -b feat/my-feature
   # or: fix/the-bug-description
   ```

2. **Make your changes** — keep commits focused; one logical change per commit

3. **Run the tests:**
   ```bash
   ./gradlew test
   ```

4. **Check the invariants** (see below) — particularly cache eviction, audit logging, and security rules

5. **If you changed templates or `app.js`**, sync the `frontend/` directory (see above)

6. **Open a PR** against `main` with:
   - A clear title describing the change (not "fix stuff" or "updates")
   - A short description of what changed and why
   - Steps to test manually if the change touches the UI or API

### PR checklist

- [ ] Tests pass (`./gradlew test`)
- [ ] Every flag write calls `auditService.logAction(...)` (if touching `FeatureFlagService`)
- [ ] Every flag write has `@CacheEvict` or `@CachePut` (if touching the cache)
- [ ] New endpoints are covered by security rules in `SecurityConfig`
- [ ] Server data going into `innerHTML` uses `escapeHtml()` (if touching `app.js`)
- [ ] New Flyway migration uses next version (`V4__`, `V5__`, ...) and does not modify existing ones

---

## Architecture invariants

These are non-negotiable constraints. PRs that violate them will be asked to fix before merging.

| Invariant | Why it matters |
|---|---|
| Every flag mutation creates an audit log | Audit immutability is a security property — operators need a complete paper trail |
| Cache evicted/updated on every write | Stale cache causes silent evaluation errors in production |
| JWT is stateless — no sessions, no cookies | Session state breaks horizontal scaling |
| `POST /api/v1/flags/evaluate` stays `permitAll` | SDKs call this on the hot path — auth overhead is unacceptable |
| Audit logs are append-only — no DELETE | Regulators and operators rely on immutability |
| Optimistic locking via `@Version` — no raw JPQL UPDATE | Bypassing JPA silently breaks concurrency control |
| No Redis — use Caffeine | Simplicity over infrastructure; see `CacheConfig.java` |
| `escapeHtml()` on all server data in `innerHTML` | XSS prevention — a direct violation ships a vulnerability |
| Webhook secrets masked in list/get responses | Secrets must only be readable at creation time |
| Promoted flags start disabled | Safety — operators choose when to enable in a new environment |

---

## Reporting bugs and security issues

**Bugs:** Open a GitHub issue with steps to reproduce, expected behavior, and actual behavior.

**Security vulnerabilities:** Do not open a public issue. Email the maintainer directly or use GitHub's private security advisory feature. Please allow time to patch before public disclosure.

---

## Good first issues

Looking for a place to start? These areas are self-contained and well-documented:

- Add a Python or Node.js SDK (follow the Java SDK structure in `sdk-java/`)
- Add pagination to the webhooks table in the UI
- Add a "copy flag key" button to the dashboard flag cards
- Write additional integration tests for the evaluate endpoint (attribute targeting edge cases)
- Add a `VIEWER` role restriction to the user management API (currently ADMIN-only in `UserController`)
