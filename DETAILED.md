# AtlasFlag - Detailed Documentation

## Architecture

```
┌──────────────────────────┐
│ Admin API / UI           │
│ (Flag Management)        │
└─────────────┬────────────┘
              │
┌─────────────▼────────────┐
│ Feature Flag Service     │
│ (Spring Boot, Java 21)   │
└──────────┬───────┬───────┘
           │       │
┌──────────▼──┐ ┌──▼──────────┐
│ Redis       │ │ PostgreSQL  │
│ (Cache)     │ │ (Source)    │
└─────────────┘ └─────────────┘
           │
┌──────────▼──────────┐
│ Client SDKs          │
│ (Java first)         │
└─────────────────────┘
```

## Configuration

Service configuration is in `service/src/main/resources/application.yml`:



**Important Configuration Notes:**

- **JWT Secret**: Must be at least 32 bytes (256 bits). Set `JWT_SECRET` environment variable in production.
- **CORS**: Allowed origins can be configured via `CORS_ALLOWED_ORIGINS` environment variable (comma-separated list).
- **Database**: PostgreSQL is required. Connection pool settings are optimized for production workloads.
- **Redis**: Used for caching. Falls back to in-memory cache if Redis is unavailable.

## Development

### Project Structure

```
atlas-flag/
├── service/           # Spring Boot service
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/
│   │   │   └── resources/
│   │   └── test/
│   └── build.gradle
├── sdk-java/          # Java SDK
│   ├── src/
│   └── build.gradle
├── infra/             # Infrastructure (Docker Compose)
│   └── docker-compose.yml
├── README.md
├── QUICKSTART.md
├── SETUP.md
└── detailed.md
```

### Building

```bash
# Build all projects
./gradlew build

# Build service only
./gradlew :service:build

# Build SDK only
./gradlew :sdk-java:build

# Clean build
./gradlew clean build
```

### Testing

```bash
# Run all tests
./gradlew test

# Run service tests
./gradlew :service:test

# Run SDK tests
./gradlew :sdk-java:test

# Run tests with coverage
./gradlew test jacocoTestReport
```

### Running Tests Locally

The service uses Testcontainers for integration tests, which requires Docker to be running.

## Design Principles

1. **Reads must never block production traffic**
   - All read operations are cached
   - Database failures don't impact flag evaluation
   - SDKs degrade gracefully

2. **Clients must degrade gracefully**
   - SDKs use local caching
   - Fallback to default values when service is unavailable
   - No exceptions thrown during evaluation

3. **All changes must be auditable**
   - Every create, update, delete is logged
   - Audit logs include before/after values
   - Immutable audit trail

4. **Rollbacks must be fast and safe**
   - Instant flag toggles
   - Version-based optimistic locking
   - No database migrations required for flag changes

5. **The platform should enable teams, not police them**
   - Simple API design
   - Self-service flag management
   - Minimal operational overhead

## Failure Modes & Mitigations

| Failure | Impact | Mitigation |
|---------|--------|-----------|
| Redis outage | Cache unavailable | Fallback to in-memory cache, direct database reads |
| Database outage | Cannot create/update flags | Cached reads continue, evaluation still works |
| Bad flag pushed | Incorrect evaluation | Instant toggle to disable, version rollback |
| Partial rollout issue | Wrong users affected | Kill switch (set enabled=false), adjust rollout percentage |
| Network partition | SDK cannot reach service | Local cache serves stale data, graceful degradation |
| Service restart | Temporary unavailability | SDK uses cached values, retries with backoff |

## Observability

### Metrics

Prometheus metrics are available at `/actuator/prometheus`:

- `http_server_requests_seconds` - HTTP request latency
- `jvm_memory_used_bytes` - JVM memory usage
- `hikari_connections_active` - Database connection pool metrics
- `cache_gets_total` - Cache hit/miss rates
- `flag_evaluations_total` - Flag evaluation counts

### Health Checks

Health endpoint at `/actuator/health` provides:

- Database connectivity
- Redis connectivity
- Application status
- Disk space
- JVM metrics

### Logging

- **Structured logging** with error IDs (UUID) for tracking
- **Log levels**: INFO (default), WARN for security, ERROR for exceptions
- **Audit logging**: Separate logger for audit events
- **Error tracking**: All exceptions include unique error ID for correlation

### Audit Logs

Audit logs are stored in the database and include:

- Entity type and ID
- Action type (CREATE, UPDATE, DELETE, ENABLE, DISABLE)
- User who performed the action
- Timestamp
- Before/after values (JSON)

Access audit logs via:
- `GET /api/v1/audit/entity/{entityType}/{entityId}` - All changes to an entity
- `GET /api/v1/audit/user/{userId}` - All changes by a user

## Security

### Authentication

- **JWT-based authentication** for all API endpoints
- Token expiration: 24 hours (configurable)
- Secret key: Minimum 256 bits (32 bytes)

### Authorization

- **Role-based access control (RBAC)**
- Roles: ADMIN, USER (extensible)
- Admin-only endpoints: Flag creation, updates, deletion
- Public endpoints: Flag evaluation

### Data Protection

- **Immutable audit logs** - Cannot be modified or deleted
- **Secrets never stored in plain text** - JWT secrets via environment variables
- **Input validation** - All inputs validated before processing
- **SQL injection protection** - JPA/Hibernate parameterized queries
- **CORS configuration** - Restricted origins in production

### Best Practices

1. **Production Deployment:**
   - Set `JWT_SECRET` environment variable (min 32 bytes)
   - Configure `CORS_ALLOWED_ORIGINS` for your domains
   - Use HTTPS only
   - Enable database SSL connections
   - Use Redis AUTH if exposed

2. **Secret Management:**
   - Never commit secrets to version control
   - Use secret management services (AWS Secrets Manager, HashiCorp Vault, etc.)
   - Rotate JWT secrets periodically

3. **Network Security:**
   - Use VPC/private networks for database and Redis
   - Restrict database access to application servers only
   - Use firewall rules to limit Redis access

## Roadmap

### Phase 1 – MVP ✅

- [x] Boolean flags
- [x] Percentage-based rollouts
- [x] Multi-environment support
- [x] Java SDK
- [x] Redis caching
- [x] Audit logs
- [x] JWT authentication
- [x] REST API

### Phase 2

- [ ] Approval workflows
- [x] Web UI for flag management
- [ ] Enhanced multi-environment support (promotion workflows)
- [ ] Flag targeting (user attributes, segments)
- [ ] Scheduled flag changes
- [ ] Flag dependencies

### Phase 3

- [ ] Event-driven propagation (Kafka/RabbitMQ)
- [ ] Multi-region deployment
- [ ] Additional SDKs (Python, Node.js, Go)
- [ ] GraphQL API
- [ ] Feature flag analytics
- [ ] A/B testing integration

## Contributing

Contributions are welcome! Please follow these guidelines:

1. **Fork the repository** and create a feature branch
2. **Follow code style** - Use the existing code style and formatting
3. **Write tests** - All new features should include tests
4. **Update documentation** - Keep README and docs up to date
5. **Submit a PR** - Include a clear description of changes

### Development Setup

1. Clone the repository
2. Ensure Java 17+ is installed and JAVA_HOME is set
3. Start infrastructure: `cd infra && docker-compose up -d`
4. Run tests: `./gradlew test`
5. Start service: `./gradlew :service:bootRun`

### Code Style

- Follow Java conventions
- Use meaningful variable and method names
- Add Javadoc for public APIs
- Keep methods focused and small
- Use dependency injection (constructor injection preferred)

## Troubleshooting

### Common Issues

**Service won't start:**
- Check Java version: `java -version` (must be 17+)
- Verify JAVA_HOME is set correctly
- Check that PostgreSQL and Redis are running: `docker ps`
- Review logs for specific errors

**Database connection errors:**
- Verify PostgreSQL is running: `docker ps | grep postgres`
- Check connection string in `application.yml`
- Test connection: `psql -h localhost -U atlasflag -d atlasflag`

**Redis connection errors:**
- Verify Redis is running: `docker ps | grep redis`
- Test connection: `redis-cli ping`
- Check Redis configuration in `application.yml`

**Flyway migration errors:**
- Ensure database exists and is accessible
- Check migration files in `service/src/main/resources/db/migration`
- Review Flyway logs for specific errors

**JWT authentication fails:**
- Verify JWT_SECRET is set (min 32 bytes)
- Check token expiration time
- Ensure token is included in Authorization header: `Bearer <token>`

For more help, see [QUICKSTART.md](QUICKSTART.md) or [SETUP.md](SETUP.md).
