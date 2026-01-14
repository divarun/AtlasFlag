# AtlasFlag

A Distributed Feature Flag & Configuration Management Platform

## Overview

AtlasFlag is a centralized feature flag and dynamic configuration platform designed to enable engineering teams to release software safely, progressively, and independently of deployment cycles.

## Features

- **Feature Flags**: Boolean flags with percentage-based rollouts
- **Environment Support**: Multi-environment flag management
- **Caching**: Redis-backed caching for low-latency evaluation
- **Audit Logging**: Complete audit trail for all changes
- **Security**: JWT-based authentication and RBAC
- **Resilient SDK**: Graceful degradation when service is unavailable

## Quick Start

### Prerequisites

- **Java 17 or higher** (Java 21 recommended)
  - ⚠️ **Important:** JAVA_HOME must point to Java 17+. Check with `java -version`
  - See [SETUP.md](SETUP.md) for detailed setup instructions
- Docker and Docker Compose
- Gradle 8.5+ (or use Gradle Wrapper - included)

### Running Locally

1. **Start Infrastructure:**
   ```bash
   cd infra
   docker-compose up -d
   ```

2. **Build and Run Service:**
   ```bash
   ./gradlew :service:bootRun
   ```

3. **Verify Service:**
   ```bash
   curl http://localhost:8080/actuator/health
   ```

For detailed setup instructions, see [QUICKSTART.md](QUICKSTART.md).

## Using the SDK

```java
// Create client
AtlasFlagClient client = new AtlasFlagClient.Builder()
    .baseUrl("http://localhost:8080")
    .environment("default")
    .cacheEnabled(true)
    .build();

// Evaluate flag
boolean enabled = client.isEnabled("my-feature", "user123", false);

// Cleanup
client.shutdown();
```

## API Endpoints

### Authentication
- `POST /api/v1/auth/login` - Get JWT token

### Feature Flags
- `POST /api/v1/flags` - Create a flag
- `GET /api/v1/flags` - List all flags
- `GET /api/v1/flags/{id}` - Get flag by ID
- `PUT /api/v1/flags/{id}` - Update flag
- `POST /api/v1/flags/{flagKey}/toggle` - Toggle flag
- `DELETE /api/v1/flags/{id}` - Delete flag
- `POST /api/v1/flags/evaluate` - Evaluate flag (public)

### Audit
- `GET /api/v1/audit/entity/{entityType}/{entityId}` - Get audit logs for entity
- `GET /api/v1/audit/user/{userId}` - Get audit logs for user

## Documentation

- [QUICKSTART.md](QUICKSTART.md) - Step-by-step getting started guide
- [SETUP.md](SETUP.md) - Detailed setup instructions
- [DETAILED.md](DETAILED.md) - Comprehensive documentation (configuration, development, architecture, etc.)

## License

MIT
