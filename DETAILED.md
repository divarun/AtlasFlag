# AtlasFlag — Technical Documentation

## Table of Contents

- [Architecture](#architecture)
- [API Reference](#api-reference)
- [Configuration](#configuration)
- [Database Schema](#database-schema)
- [Security](#security)
- [Caching](#caching)
- [Observability](#observability)
- [Design Principles](#design-principles)
- [Failure Modes](#failure-modes)
- [Development Guide](#development-guide)
- [Deployment](#deployment)
- [Roadmap](#roadmap)
- [Contributing](#contributing)
- [Troubleshooting](#troubleshooting)

---

## Architecture

### System Overview

```
Browser / SDK Clients
        │
        ▼
┌──────────────────────────────────────────┐
│          Spring Boot Service             │
│                                          │
│  ┌───────────────┐  ┌─────────────────┐  │
│  │   REST API    │  │    Web UI       │  │
│  │ /api/v1/...   │  │ /dashboard      │  │
│  └──────┬────────┘  └─────────────────┘  │
│         │                                │
│  ┌──────▼────────────────────────────┐   │
│  │       FeatureFlagService          │   │
│  │  + Caffeine Cache (in-memory)     │   │
│  └──────┬────────────────────────────┘   │
│         │                                │
│  ┌──────▼────────────────────────────┐   │
│  │       AuditService (@Async)       │   │
│  └──────┬────────────────────────────┘   │
└─────────┼────────────────────────────────┘
          │
          ▼
    PostgreSQL 16
    ┌───────────────┐
    │  users        │
    │  feature_flags│
    │  audit_logs   │
    └───────────────┘
```

### Request Flow — Flag Evaluation

```
POST /api/v1/flags/evaluate
    │
    ├─ No auth required (public endpoint)
    │
    ▼
FeatureFlagService.evaluateFlag()
    │
    ├─ getFlag(flagKey, environment)
    │      │
    │      ├─ Caffeine cache hit → return cached FeatureFlag
    │      └─ cache miss → DB read → cache write → return
    │
    ├─ flag disabled? → return defaultValue
    │
    ├─ targetingRules set AND attributes provided?
    │      ├─ rules match → continue
    │      └─ no match → return defaultValue + reason "TARGETING_NO_MATCH"
    │
    ├─ rolloutPercentage set?
    │      └─ hash(userId) % 100 < rolloutPercentage → enabled
    │
    ├─ EvaluationAnalyticsService.record() — @Async, non-blocking
    │
    └─ return FlagEvaluationResponse { enabled, reason, value }
```

### Request Flow — Flag Mutation

```
PUT /api/v1/flags/{id}
    │
    ├─ JwtAuthenticationFilter extracts role from JWT
    │
    ▼
FeatureFlagService.updateFlag()
    │
    ├─ Optimistic lock check (@Version field)
    ├─ Update DB record
    ├─ @CacheEvict removes stale entry
    └─ AuditService.logAction() — @Async, non-blocking
```

### Module Layout

```
atlas-flag/
├── service/                         # Spring Boot application
│   └── src/main/java/com/atlasflag/
│       ├── config/
│       │   ├── AppConfig.java       # PasswordEncoder bean + DataInitializer
│       │   ├── CacheConfig.java     # Caffeine CacheManager (in-memory)
│       │   └── SecurityConfig.java  # JWT filter chain + CORS
│       ├── controller/
│       │   ├── AuthController.java
│       │   ├── FeatureFlagController.java  # flags + analytics + SSE stream
│       │   ├── AuditController.java
│       │   ├── UserController.java         # user management (ADMIN only)
│       │   ├── WebhookController.java      # webhook CRUD (ADMIN only)
│       │   └── ViewController.java         # serves HTML pages
│       ├── domain/                         # JPA entities
│       ├── dto/                            # request/response objects
│       ├── repository/                     # Spring Data repositories
│       ├── security/
│       │   ├── JwtTokenProvider.java
│       │   └── JwtAuthenticationFilter.java
│       └── service/
│           ├── FeatureFlagService.java
│           ├── AuthenticationService.java
│           ├── AuditService.java
│           ├── WebhookService.java
│           ├── EvaluationAnalyticsService.java  # async counter, analytics queries
│           └── FlagChangePublisher.java          # SSE emitter registry
├── sdk-java/                        # Java client SDK
├── frontend/                        # Static files for Vercel
└── infra/
    └── docker-compose.yml           # PostgreSQL (local dev only)
```

---

## Spring Boot Starter

The starter is the recommended integration for Spring Boot applications.

### Installation

```gradle
repositories {
    maven {
        url = uri("https://maven.pkg.github.com/divarun/AtlasFlag")
        credentials {
            username = project.findProperty("gpr.user") ?: System.getenv("GITHUB_ACTOR")
            password = project.findProperty("gpr.key")  ?: System.getenv("GITHUB_TOKEN")
        }
    }
}
dependencies {
    implementation 'com.atlasflag:atlas-flag-spring-boot-starter:1.0.0-SNAPSHOT'
    implementation 'org.springframework.boot:spring-boot-starter-aop' // needed for @FeatureFlag
}
```

### Configuration

```yaml
# application.yml
atlasflag:
  base-url: https://your-backend.onrender.com  # required
  environment: PRODUCTION                        # default: DEVELOPMENT
  cache:
    enabled: true                                # default: true
    ttl-seconds: 60                              # default: 60
  http:
    connect-timeout-seconds: 2                   # default: 2
    read-timeout-seconds: 3                      # default: 3
```

All properties except `base-url` are optional.

### Auto-wired bean

When `atlasflag.base-url` is set, an `AtlasFlagClient` bean is registered automatically:

```java
@Autowired
private AtlasFlagClient flags;

boolean showFeature = flags.isEnabled("my-flag", currentUserId, false);
```

### @FeatureFlag — method interception

Gates a method behind a feature flag. When the flag is **disabled**, the method body is skipped and a zero-value is returned based on the return type:

| Return type | Returned when disabled |
|---|---|
| `void` | (method skipped silently) |
| `boolean` | `false` |
| `int / long / double` | `0` |
| `Optional` | `Optional.empty()` |
| `List / Set / Map` | empty immutable collection |
| Any object | `null` |

```java
@Service
public class CheckoutService {

    @FeatureFlag("new-checkout")
    public CheckoutResult runNewFlow(Order order) {
        // only runs when "new-checkout" is enabled in PRODUCTION
    }

    @FeatureFlag(value = "beta-pricing", defaultValue = true, environment = "STAGING")
    public PricingResult computePrice(String userId) {
        // defaultValue = true means: use this result if service is unreachable
        // environment = "STAGING" overrides the configured environment for this flag
    }
}
```

Requires `spring-boot-starter-aop` on the classpath.

### @ConditionalOnFeatureFlag — startup-time bean gating

Registers a bean only when a flag is in a specific state. Evaluated once at context startup via a direct HTTP call to the flag service.

```java
// Bean created only when "experimental-cache" is enabled
@Bean
@ConditionalOnFeatureFlag("experimental-cache")
public CacheManager experimentalCache() { ... }

// Bean created only when "legacy-api" is DISABLED
@Bean
@ConditionalOnFeatureFlag(value = "legacy-api", match = false, defaultValue = true)
public ApiRouter modernRouter() { ... }
```

**Startup impact:** Each annotation makes one HTTP call (2s timeout by default). If the flag service is unreachable, `defaultValue` is used.

### UserIdProvider — custom user resolution

The starter resolves the current userId via the `UserIdProvider` bean:

- **Default with Spring Security:** reads `SecurityContextHolder.getContext().getAuthentication().getName()`
- **Default without Spring Security:** always returns `null` (rollouts disabled)
- **Custom:** register your own bean

```java
@Bean
public UserIdProvider userIdProvider(HttpServletRequest request) {
    return () -> request.getHeader("X-User-Id");
}
```

---

## API Reference

Base URL: `http://localhost:8080` (local) or your deployed backend URL

All endpoints except `/api/v1/auth/login`, `/api/v1/flags/evaluate`, `/api/v1/flags/evaluate/bulk`, and `/api/v1/flags/stream` require:
```
Authorization: Bearer <jwt-token>
```

### Authentication

#### POST /api/v1/auth/login

Request:
```json
{ "username": "admin", "password": "admin123" }
```

Response `200 OK`:
```json
{
  "token": "eyJhbGciOiJIUzI1NiJ9...",
  "type": "Bearer",
  "username": "admin",
  "role": "ADMIN"
}
```

Response `401 Unauthorized`:
```json
{ "message": "Invalid credentials" }
```

---

### Feature Flags

#### POST /api/v1/flags

Create a feature flag. Requires authenticated user.

Request:
```json
{
  "flagKey": "new-checkout",
  "name": "New Checkout Flow",
  "description": "Redesigned checkout experience",
  "environment": "DEVELOPMENT",
  "enabled": false,
  "rolloutPercentage": 0,
  "defaultValue": false,
  "flagType": "BOOLEAN",
  "stringValue": null,
  "targetingRules": null
}
```

`flagType` values: `BOOLEAN` (default) · `STRING` · `NUMBER` · `JSON`

`targetingRules` JSON shape (when set):
```json
{
  "match": "all",
  "rules": [
    { "attribute": "plan", "operator": "eq",  "value": "pro" },
    { "attribute": "country", "operator": "in", "value": "DE,US,UK" }
  ]
}
```
Supported operators: `eq` · `neq` · `contains` · `startsWith` · `endsWith` · `in` · `notIn` · `gt` · `lt` · `gte` · `lte`

Response `201 Created` → `FeatureFlagDTO`

#### GET /api/v1/flags

List flags by environment.

Query params: `environment` (default: `default`)

Response `200 OK` → `List<FeatureFlagDTO>`

#### GET /api/v1/flags/{id}

Get flag by database ID.

Response `200 OK` → `FeatureFlagDTO`

#### PUT /api/v1/flags/{id}

Update flag. Includes optimistic locking — pass `version` field to prevent concurrent edit conflicts.

Request: same shape as POST

Response `200 OK` → updated `FeatureFlagDTO`

Response `409 Conflict` if another user edited simultaneously.

#### POST /api/v1/flags/{flagKey}/toggle

Toggle enabled state. Atomic flip.

Query params: `environment` (default: `default`)

Response `200 OK` → updated `FeatureFlagDTO`

#### DELETE /api/v1/flags/{id}

Delete a flag. Audit log entry is created.

Response `204 No Content`

#### POST /api/v1/flags/{id}/promote

Copy a flag's configuration to another environment. The promoted flag starts **disabled** in the target environment — enable it when ready.

Query params: `targetEnvironment` (required)

Response `201 Created` → `FeatureFlagDTO` for the new flag in the target environment.

Error `409 Conflict` if a flag with the same key already exists in the target environment.

---

#### GET /api/v1/flags (with search)

Now accepts an optional `search` query param to filter by flag key prefix/substring.

```bash
curl "http://localhost:8080/api/v1/flags?environment=PRODUCTION&search=checkout" \
  -H "Authorization: Bearer $TOKEN"
```

---

#### POST /api/v1/flags/evaluate — **PUBLIC**

Evaluate a single flag for a specific user. No authentication required. Used by SDKs.

Request:
```json
{
  "flagKey": "new-checkout",
  "environment": "PRODUCTION",
  "userId": "user-42",
  "attributes": {
    "plan": "pro",
    "country": "DE"
  }
}
```

`userId` and `attributes` are optional. Omitting `attributes` skips targeting rule evaluation.

Response `200 OK`:
```json
{
  "flagKey": "new-checkout",
  "enabled": true,
  "reason": "ROLLOUT_PERCENTAGE",
  "value": null
}
```

`value` is non-null only for `STRING`, `NUMBER`, and `JSON` flag types when the flag evaluates to enabled.

Reason values: `FLAG_ENABLED` · `FLAG_DISABLED` · `FLAG_NOT_FOUND` · `ROLLOUT_PERCENTAGE` · `ROLLOUT_EXCLUDED` · `TARGETING_NO_MATCH`

---

### User Management

Requires `ADMIN` role.

#### GET /api/v1/users

List all users. Returns id, username, email, role, createdAt (no password hashes).

#### POST /api/v1/users

Create a new user.

Request:
```json
{ "username": "alice", "email": "alice@example.com", "password": "securepass", "role": "USER" }
```

Roles: `ADMIN` · `USER` · `VIEWER`

Response `201 Created` → `UserDTO`

#### PUT /api/v1/users/{id}/password

Change a user's password (ADMIN can change any user's password).

Request:
```json
{ "password": "newpassword123" }
```

#### DELETE /api/v1/users/{id}

Delete a user account.

---

#### POST /api/v1/flags/evaluate/bulk — **PUBLIC**

Evaluate up to 100 flags in a single request. Returns a map of flagKey → evaluation result.

Request:
```json
{
  "flagKeys": ["new-checkout", "dark-mode", "beta-api"],
  "environment": "PRODUCTION",
  "userId": "user-42",
  "attributes": { "plan": "pro" }
}
```

Response `200 OK`:
```json
{
  "new-checkout": { "flagKey": "new-checkout", "enabled": true,  "reason": "ROLLOUT_PERCENTAGE" },
  "dark-mode":    { "flagKey": "dark-mode",    "enabled": false, "reason": "FLAG_DISABLED" },
  "beta-api":     { "flagKey": "beta-api",     "enabled": false, "reason": "FLAG_NOT_FOUND" }
}
```

---

#### GET /api/v1/flags/stream — **PUBLIC**

Subscribe to real-time flag change notifications via Server-Sent Events (SSE). SDK clients use this to update their local cache without polling.

Query params: `environment` (default: `default`)

Response: `text/event-stream`

Events:
- `connected` — sent once on connect: `{"status":"connected"}`
- `FLAG_CHANGED` — sent on any flag mutation:

```json
{
  "flagKey": "dark-mode",
  "environment": "PRODUCTION",
  "enabled": true,
  "value": null,
  "flagType": "BOOLEAN",
  "deleted": false
}
```

The browser's `EventSource` API reconnects automatically on timeout (emitter TTL: 5 minutes).

---

#### GET /api/v1/flags/analytics

Bulk evaluation counts for all flags in an environment. Useful for the dashboard's 24h badge column.

Query params: `environment` (default: `default`) · `hours` (default: `24`, max: `168`)

Response `200 OK`:
```json
{
  "dark-mode":    1543,
  "new-checkout": 287
}
```

#### GET /api/v1/flags/{flagKey}/analytics

Detailed hourly evaluation breakdown for a single flag.

Query params: `environment` · `hours`

Response `200 OK`:
```json
{
  "flagKey": "dark-mode",
  "environment": "PRODUCTION",
  "hours": 24,
  "totalEvaluations": 1543,
  "trueCount": 1230,
  "falseCount": 313,
  "truePercent": 79.7,
  "hourly": [
    { "hour": "2026-05-29T10:00:00Z", "trueCount": 48, "falseCount": 12 }
  ]
}
```

---

### Webhooks

Requires `ADMIN` role.

#### GET /api/v1/webhooks

List all configured webhooks. Secrets are masked as `••••••••`.

#### POST /api/v1/webhooks

Register a new webhook endpoint.

Request:
```json
{ "url": "https://your-service.com/hooks/atlasflag", "secret": "your-secret-min-16-chars" }
```

Response `201 Created` → `WebhookDTO` (secret shown once — save it immediately).

**Verifying webhook signatures:**
```java
Mac mac = Mac.getInstance("HmacSHA256");
mac.init(new SecretKeySpec(secret.getBytes(UTF_8), "HmacSHA256"));
String expected = "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes(UTF_8)));
boolean valid = expected.equals(request.getHeader("X-AtlasFlag-Signature"));
```

#### POST /api/v1/webhooks/{id}/toggle

Enable or disable a webhook without deleting it.

#### DELETE /api/v1/webhooks/{id}

Remove a webhook permanently.

**Webhook payload shape:**
```json
{
  "event":       "FLAG_ENABLED",
  "flagKey":     "new-checkout",
  "environment": "PRODUCTION",
  "enabled":     true,
  "triggeredBy": "admin",
  "timestamp":   "2025-05-29T12:00:00Z"
}
```

Event types: `FLAG_CREATED` · `FLAG_UPDATED` · `FLAG_ENABLED` · `FLAG_DISABLED` · `FLAG_DELETED` · `FLAG_PROMOTED`

Request headers: `X-AtlasFlag-Event`, `X-AtlasFlag-Signature`

---

### Audit Logs

Requires `ADMIN` role.

#### GET /api/v1/audit/entity/{entityType}/{entityId}

All audit events for a specific entity. Paginated.

Query params: `page` (0-based) · `size` (default 20)

#### GET /api/v1/audit/user/{userId}

All audit events created by a user. Paginated.

Query params: `page` · `size`

Response shape (paginated):
```json
{
  "content": [
    {
      "id": 1,
      "entityType": "FeatureFlag",
      "entityId": 42,
      "action": "UPDATE",
      "userId": "admin",
      "userEmail": null,
      "changes": "{\"old\":{...},\"new\":{...}}",
      "timestamp": "2025-05-29T10:00:00Z",
      "ipAddress": "127.0.0.1"
    }
  ],
  "totalPages": 3,
  "totalElements": 47,
  "number": 0
}
```

---

## Configuration

All configuration is in `service/src/main/resources/application.yml`. Override via environment variables in production.

### Environment Variables

| Variable | Default | Description |
|---|---|---|
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/atlasflag` | Full JDBC URL |
| `DATABASE_USERNAME` | `atlasflag` | DB username |
| `DATABASE_PASSWORD` | `atlasflag` | DB password |
| `JWT_SECRET` | dev default (insecure) | Must be ≥ 32 chars in production |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:8080` | Comma-separated list |
| `PORT` | `8080` | HTTP server port |
| `DB_POOL_SIZE` | `5` | HikariCP maximum pool size |
| `DB_SSL_MODE` | `prefer` | PostgreSQL SSL mode (`require` for Neon/cloud) |

### Cache Tuning

In `application.yml`:
```yaml
atlasflag:
  cache:
    ttl: 300   # seconds — how long flags are cached in memory
```

Or via `spring.cache.caffeine.spec`:
```yaml
spring:
  cache:
    caffeine:
      spec: maximumSize=1000,expireAfterWrite=300s
```

### JWT Tuning

```yaml
atlasflag:
  jwt:
    secret: ${JWT_SECRET:your-secret}
    expiration: 86400000   # milliseconds — 24 hours
```

---

## Database Schema

Managed by Flyway. Migrations live in `service/src/main/resources/db/migration/`.

### Tables

**users**
```sql
id           BIGSERIAL PRIMARY KEY
username     VARCHAR(255) NOT NULL UNIQUE
email        VARCHAR(255) NOT NULL UNIQUE
password_hash VARCHAR(255) NOT NULL  -- BCrypt
role         VARCHAR(50) NOT NULL    -- ADMIN | USER | VIEWER
created_at   TIMESTAMP NOT NULL
updated_at   TIMESTAMP NOT NULL
```

**feature_flags**
```sql
id                BIGSERIAL PRIMARY KEY
flag_key          VARCHAR(255) NOT NULL
name              VARCHAR(255) NOT NULL
description       TEXT
enabled           BOOLEAN NOT NULL DEFAULT FALSE
rollout_percentage INTEGER               -- 0-100
environment       VARCHAR(100) NOT NULL
default_value     BOOLEAN NOT NULL DEFAULT FALSE
flag_type         VARCHAR(20) NOT NULL DEFAULT 'BOOLEAN'  -- BOOLEAN|STRING|NUMBER|JSON
string_value      TEXT                   -- config value for non-boolean types
targeting_rules   TEXT                   -- JSON rules for attribute targeting
created_by        VARCHAR(255) NOT NULL
created_at        TIMESTAMP NOT NULL
updated_by        VARCHAR(255)
updated_at        TIMESTAMP NOT NULL
version           BIGINT NOT NULL DEFAULT 0   -- optimistic lock
UNIQUE(flag_key, environment)
```

**audit_logs**
```sql
id           BIGSERIAL PRIMARY KEY
entity_type  VARCHAR(100) NOT NULL
entity_id    BIGINT
action       VARCHAR(50) NOT NULL    -- CREATE|UPDATE|DELETE|ENABLE|DISABLE|PROMOTE
user_id      VARCHAR(255) NOT NULL
user_email   VARCHAR(255)
changes      TEXT                    -- JSON: {"old":{...},"new":{...}}
timestamp    TIMESTAMP NOT NULL
ip_address   VARCHAR(45)
```

**flag_evaluations** *(analytics — one row per flag/env/result/hour)*
```sql
id           BIGSERIAL PRIMARY KEY
flag_key     VARCHAR(255) NOT NULL
environment  VARCHAR(100) NOT NULL
result       BOOLEAN NOT NULL
hour_bucket  TIMESTAMP NOT NULL       -- truncated to the hour
count        BIGINT NOT NULL DEFAULT 1
UNIQUE(flag_key, environment, result, hour_bucket)
```

**webhooks**
```sql
id           BIGSERIAL PRIMARY KEY
url          VARCHAR(2048) NOT NULL
secret       VARCHAR(255) NOT NULL
enabled      BOOLEAN NOT NULL DEFAULT TRUE
created_by   VARCHAR(255) NOT NULL
created_at   TIMESTAMP NOT NULL
```

---

## Security

### Authentication Flow

1. Client POSTs `{username, password}` to `/api/v1/auth/login`
2. `AuthenticationService` looks up user, validates BCrypt hash
3. `JwtTokenProvider` issues a signed JWT containing `sub` (username) and `role` claim
4. Client includes `Authorization: Bearer <token>` on all subsequent requests
5. `JwtAuthenticationFilter` validates signature + expiry, sets `SecurityContext` with correct `ROLE_*` authority

### Roles and Permissions

| Role | Flag CRUD | Audit Logs | Toggle |
|---|---|---|---|
| `ADMIN` | ✅ | ✅ | ✅ |
| `USER` | ✅ | ❌ | ✅ |
| `VIEWER` | ❌ (read only) | ❌ | ❌ |

### Production Checklist

- [ ] Set `JWT_SECRET` env var (≥ 32 chars, high entropy)
- [ ] Set `DATABASE_PASSWORD` — do not use the default `atlasflag`
- [ ] Set `CORS_ALLOWED_ORIGINS` to your exact frontend URL
- [ ] Change default admin password (login and update via SQL or future user management API)
- [ ] Enable HTTPS — never run HTTP in production
- [ ] Restrict database access to the application server's IP only
- [ ] Set `management.endpoint.health.show-details: never` if health endpoint is public

---

## Caching

AtlasFlag uses **Caffeine** for in-memory caching — no external cache server required.

### Cached Data

Only `feature_flags` lookups are cached (the hot read path for evaluation):

| Cache name | Key pattern | TTL | Eviction trigger |
|---|---|---|---|
| `flags` | `flag:{flagKey}:env:{environment}` | 300s (configurable) | Update, toggle, delete |

### Upgrading to a Distributed Cache

The `@Cacheable` / `@CacheEvict` / `@CachePut` annotations are cache-provider agnostic. To add Redis later:

1. Add `spring-boot-starter-data-redis` to `build.gradle`
2. Replace `CacheConfig.java` with a `RedisCacheManager` bean
3. Add `spring.data.redis.*` to `application.yml`

No service code changes required.

---

## Observability

### Health

```
GET /actuator/health
```

Reports: database connectivity · application status

### Metrics

Prometheus-compatible metrics at `/actuator/metrics`:

- `http_server_requests_seconds` — request latency by endpoint
- `jvm_memory_used_bytes` — heap/non-heap
- `hikari_connections_active` — DB pool utilization
- `cache_gets_total{result="hit"}` — cache hit rate

### Audit Events

Action types logged to `audit_logs`:

| Action | Trigger |
|---|---|
| `CREATE` | New flag created |
| `UPDATE` | Flag fields changed |
| `ENABLE` | Toggle → true |
| `DISABLE` | Toggle → false |
| `DELETE` | Flag deleted |
| `PROMOTE` | Flag copied to another environment |

All audit writes are `@Async` — they do not block the API response.

---

## Design Principles

1. **Reads must never block production traffic** — evaluation is cached, DB failures don't break evaluation, SDKs degrade gracefully
2. **All changes are auditable** — every mutation creates an audit record before returning
3. **Rollbacks are instant** — toggle a flag off at any time; no redeploy, no migration
4. **No external runtime dependencies** — Caffeine is in-process; the only required external service is PostgreSQL
5. **The platform enables teams, not polices them** — simple API, self-service UI, minimal operational overhead

---

## Failure Modes

| Failure | Impact | Mitigation |
|---|---|---|
| DB outage | Cannot create/update flags | Cached evaluations continue serving |
| Service restart | Brief unavailability | SDK uses cached values during restart |
| Bad flag pushed | Wrong evaluation | Instant toggle to disable |
| Partial rollout issue | Wrong % of users affected | Kill switch: `enabled=false` |
| Network partition | SDK cannot reach service | Local SDK cache serves stale data |
| Concurrent edits | Lost update | Optimistic locking (`version` field) → `409 Conflict` |

---

## Development Guide

### Prerequisites

- Java 21 ([Adoptium](https://adoptium.net/))
- Docker (for PostgreSQL)
- Gradle (wrapper included)

### Local Setup

```bash
# Start PostgreSQL
cd infra && docker-compose up -d && cd ..

# Run with live reload
./gradlew :atlas-flag-service:bootRun

# Or build a JAR
./gradlew :atlas-flag-service:build -x test
java -jar service/build/libs/atlas-flag-service-1.0.0-SNAPSHOT.jar
```

### Running Tests

```bash
./gradlew :atlas-flag-service:test   # unit + integration tests (needs Docker for Testcontainers)
./gradlew :atlas-flag-sdk-java:test  # SDK tests
./gradlew test                       # all modules
```

Integration tests use Testcontainers — Docker must be running.

### Building the SDK

```bash
./gradlew :atlas-flag-sdk-java:build              # produces sdk-java/build/libs/*.jar
./gradlew :atlas-flag-sdk-java:publishToMavenLocal # install locally for testing
```

---

## Deployment

Full step-by-step guide: **[DEPLOYMENT.md](DEPLOYMENT.md)**

### Stack

| Layer | Platform | Cost |
|---|---|---|
| Backend | Render (free web service) | Free |
| Database | Neon (serverless PostgreSQL) | Free |
| Frontend | Vercel (static hosting) | Free |

### Backend — Render

`render.yaml` in the repo root is the deployment blueprint. Connect your GitHub repo on Render and it configures everything automatically.

```bash
# Build command (Render runs this)
./gradlew :atlas-flag-service:build -x test

# Start command (JAR name matches the project artifactId)
java $JAVA_OPTS -jar service/build/libs/atlas-flag-service-1.0.0-SNAPSHOT.jar
```

Required env vars on Render:

```
DATABASE_URL=jdbc:postgresql://ep-xxx.neon.tech/neondb?sslmode=require
DATABASE_USERNAME=<neon-user>
DATABASE_PASSWORD=<neon-password>
JWT_SECRET=<auto-generated by Render>
CORS_ALLOWED_ORIGINS=https://your-app.vercel.app
JAVA_VERSION=21
JAVA_OPTS=-Xmx400m -Xms200m -XX:+UseSerialGC
```

### Database — Neon

Create a free project at [neon.tech](https://neon.tech). Use the **JDBC** connection string format.
The `?sslmode=require` suffix is mandatory for Neon connections.

### Frontend — Vercel

1. Set root directory to `frontend/` in Vercel project settings
2. Edit `vercel.json` — replace `YOUR_RENDER_URL` with your Render service URL
3. Push — Vercel deploys automatically on every commit

---

## Roadmap

### Phase 1 — Core ✅
- [x] Boolean flags with percentage rollouts
- [x] Multi-environment (`DEVELOPMENT`, `STAGING`, `PRODUCTION`)
- [x] Environment promotion (copy flag config to next environment)
- [x] Flag search / filter by key
- [x] Java SDK — bulk evaluation, local caching, background refresh
- [x] Bulk evaluate endpoint (up to 100 flags per call)
- [x] Webhook notifications (HMAC-SHA256 signed, 6 event types)
- [x] Immutable audit trail
- [x] JWT auth + RBAC (ADMIN · USER · VIEWER)
- [x] User management API (create, delete, change password)
- [x] Web dashboard (flags · audit logs · webhooks)
- [x] Caffeine in-memory cache
- [x] Vercel + Render + Neon deployment support

### Phase 2 — Advanced Flag Control ✅
- [x] User attribute targeting (targeting rules with AND/OR, 11 operators)
- [x] Remote configuration (STRING/NUMBER/JSON flag types with typed SDK accessors)
- [x] Evaluation analytics (per-flag hourly counters, 24h dashboard view)
- [x] SSE streaming (zero-downtime cache updates — no polling needed)
- [ ] Approval workflows (flag changes require review before activating in PRODUCTION)
- [ ] Scheduled flag changes (turn on at a specific time)
- [ ] Flag dependencies (flag B requires flag A)
- [ ] User management UI (in dashboard)

### Phase 3 — Scale
- [ ] Python / Node.js / Go SDKs
- [ ] GraphQL API
- [ ] A/B testing integration
- [ ] Unique user counts in analytics
- [ ] Multi-region deployment
- [ ] Webhook retry with exponential backoff

---

## Contributing

1. Fork and clone the repo
2. Create a feature branch: `git checkout -b feat/my-feature`
3. Run existing tests: `./gradlew test`
4. Add tests for your change
5. Submit a PR with a clear description

### Code Conventions

- Constructor injection everywhere — no field injection
- Service layer owns business logic — controllers are thin
- New mutations must create an audit log entry via `AuditService.logAction()`
- Cache eviction must accompany any write to `feature_flags`
- No unchecked exceptions from SDK methods — always return a safe default

---

## Troubleshooting

### Service won't start

```
# Check Java version
java -version   # must be 17+

# Check DB is up
docker ps | grep postgres
curl http://localhost:8080/actuator/health
```

### Flyway migration errors

```bash
# Connect and inspect
psql -h localhost -U atlasflag -d atlasflag

# List applied migrations
SELECT * FROM flyway_schema_history;
```

If you modified a migration that was already applied, drop the DB and recreate:
```bash
docker-compose down -v && docker-compose up -d
```

### JWT issues

- `401 Unauthorized` on all requests → token expired (24h TTL) — log in again
- `403 Forbidden` on audit endpoints → user has `USER` role, not `ADMIN`
- `IllegalStateException: JWT secret must be at least 32 bytes` → set `JWT_SECRET` env var

### CORS errors in browser

Set `CORS_ALLOWED_ORIGINS` to your exact frontend origin (no trailing slash):
```
CORS_ALLOWED_ORIGINS=https://your-app.vercel.app
```
