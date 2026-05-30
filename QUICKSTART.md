# Quick Start Guide

Get AtlasFlag running locally in under 5 minutes.

## Prerequisites

- **Java 21** — [Download from Adoptium](https://adoptium.net/)
  - Verify: `java -version` must show 21+
  - Set `JAVA_HOME` if needed — see [SETUP.md](SETUP.md)
- **Docker** — for PostgreSQL

## Step 1: Start PostgreSQL

```bash
cd infra
docker-compose up -d
cd ..
```

This starts PostgreSQL on port 5432. No Redis required.

## Step 2: Start the Service

```bash
./gradlew :atlas-flag-service:bootRun
```

Wait for `Started AtlasFlagApplication` in the logs (~10 seconds).

## Step 3: Open the Dashboard

Navigate to [http://localhost:8080](http://localhost:8080)

**Default login:**
- Username: `admin`
- Password: `admin123`

Or use the API directly:

```bash
# Get a JWT token
curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}' | python3 -m json.tool

# Save the token
TOKEN="paste-your-token-here"
```

## Step 4: Create Your First Flag

```bash
curl -X POST http://localhost:8080/api/v1/flags \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "flagKey": "new-feature",
    "name": "New Feature",
    "description": "My first feature flag",
    "environment": "DEVELOPMENT",
    "enabled": true,
    "rolloutPercentage": 100,
    "defaultValue": false
  }'
```

## Step 5: Evaluate the Flag

```bash
# Public — no auth required
curl -X POST http://localhost:8080/api/v1/flags/evaluate \
  -H "Content-Type: application/json" \
  -d '{"flagKey":"new-feature","environment":"DEVELOPMENT","userId":"user-1"}'
```

Response:
```json
{"flagKey":"new-feature","enabled":true,"reason":"FLAG_ENABLED"}
```

## Step 6: Toggle Off (Emergency Kill Switch)

```bash
curl -X POST "http://localhost:8080/api/v1/flags/new-feature/toggle?environment=DEVELOPMENT" \
  -H "Authorization: Bearer $TOKEN"
```

## Step 7: Use the Java SDK

```java
AtlasFlagClient client = new AtlasFlagClient.Builder()
    .baseUrl("http://localhost:8080")
    .environment("DEVELOPMENT")
    .cacheEnabled(true)
    .build();

boolean enabled = client.isEnabled("new-feature", "user-1", false);
System.out.println("Feature enabled: " + enabled);

client.shutdown();
```

## Common Operations

```bash
# List flags in an environment
curl "http://localhost:8080/api/v1/flags?environment=DEVELOPMENT" \
  -H "Authorization: Bearer $TOKEN"

# View audit log for a flag (requires ADMIN role)
curl "http://localhost:8080/api/v1/audit/user/admin" \
  -H "Authorization: Bearer $TOKEN"

# Health check
curl http://localhost:8080/actuator/health
```

## Troubleshooting

| Problem | Fix |
|---|---|
| Service won't start | Check `java -version` is 21+ |
| DB connection error | Check `docker ps \| grep postgres` |
| 401 on all requests | Token expired — log in again |
| 403 on audit endpoints | Your user needs `ADMIN` role |

For setup help → [SETUP.md](SETUP.md)
For full API reference → [DETAILED.md](DETAILED.md)
