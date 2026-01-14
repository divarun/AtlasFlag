# Quick Start Guide

## Prerequisites

- **Java 17 or higher** (Java 21 recommended)
  - ⚠️ **CRITICAL:** JAVA_HOME must point to Java 17+. 
  - Check with: `java -version`
  - If you see Java 8 or lower, see [SETUP.md](SETUP.md) for setup instructions
- Gradle 8.5+ (or use Gradle Wrapper - included)
- Docker and Docker Compose

## Step 1: Start Infrastructure

```bash
cd infra
docker-compose up -d
```

This starts:
- PostgreSQL on port 5432
- Redis on port 6379

## Step 2: Build and Run Service

```bash
# From project root
./gradlew :service:bootRun

# Or from service directory
cd service
../gradlew bootRun
```

The service will start on `http://localhost:8080`

## Step 3: Verify Service

```bash
# Health check
curl http://localhost:8080/actuator/health

# Create a user first (via database)
# Connect to PostgreSQL and run:
# psql -h localhost -U atlasflag -d atlasflag
# INSERT INTO users (username, email, role) VALUES ('admin', 'admin@example.com', 'ADMIN');

# Get JWT token (username must exist in database)
curl -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username": "admin", "password": ""}'
  
# Note: For MVP, password validation is not yet implemented.
# The username must exist in the users table.
```

## Step 4: Create a Feature Flag

```bash
# Replace TOKEN with the token from step 3
TOKEN="your-jwt-token"

curl -X POST http://localhost:8080/api/v1/flags \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "flagKey": "new-feature",
    "name": "New Feature",
    "description": "Enable new feature",
    "enabled": true,
    "environment": "default",
    "defaultValue": false
  }'
```

## Step 5: Evaluate a Flag

```bash
# Public endpoint - no auth required
curl -X POST http://localhost:8080/api/v1/flags/evaluate \
  -H "Content-Type: application/json" \
  -d '{
    "flagKey": "new-feature",
    "environment": "default",
    "userId": "user123"
  }'
```

## Step 6: Use the SDK

Add the SDK to your project:

**Gradle:**
```gradle
dependencies {
    implementation 'com.atlasflag:atlas-flag-sdk-java:1.0.0-SNAPSHOT'
}
```

**Maven:**
```xml
<dependency>
    <groupId>com.atlasflag</groupId>
    <artifactId>atlas-flag-sdk-java</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

Then use it:

```java
import com.atlasflag.sdk.AtlasFlagClient;

// Create client
AtlasFlagClient client = new AtlasFlagClient.Builder()
    .baseUrl("http://localhost:8080")
    .environment("default")
    .cacheEnabled(true)
    .build();

// Evaluate flag
boolean enabled = client.isEnabled("new-feature", "user123", false);

if (enabled) {
    // Feature is enabled
    System.out.println("New feature is enabled!");
} else {
    // Feature is disabled
    System.out.println("New feature is disabled");
}

// Cleanup
client.shutdown();
```

## Common Operations

### Toggle a Flag

```bash
curl -X POST "http://localhost:8080/api/v1/flags/new-feature/toggle?environment=default" \
  -H "Authorization: Bearer $TOKEN"
```

### List All Flags

```bash
curl http://localhost:8080/api/v1/flags?environment=default \
  -H "Authorization: Bearer $TOKEN"
```

### View Audit Logs

```bash
curl "http://localhost:8080/api/v1/audit/entity/FeatureFlag/1" \
  -H "Authorization: Bearer $TOKEN"
```

## Troubleshooting

### Service won't start
- Check that PostgreSQL and Redis are running: `docker ps`
- Check logs: `docker-compose logs`

### Database connection errors
- Verify PostgreSQL is running: `docker ps | grep postgres`
- Check connection: `psql -h localhost -U atlasflag -d atlasflag`

### Redis connection errors
- Verify Redis is running: `docker ps | grep redis`
- Test connection: `redis-cli ping`

## Next Steps

- Read the [README.md](README.md) for more details
- Check [docs/architecture.md](docs/architecture.md) for system design
- Review [docs/prd.md](docs/prd.md) for product requirements
