# ── Build stage ────────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk-jammy AS builder

WORKDIR /app

# Copy wrapper and project config first — these layers are cached unless versions change
COPY gradle/         gradle/
COPY gradlew         gradlew
COPY gradlew.bat     gradlew.bat
COPY settings.gradle settings.gradle
COPY build.gradle    build.gradle
COPY gradle.properties gradle.properties
COPY sdk-java/build.gradle               sdk-java/build.gradle
COPY starter-spring-boot/build.gradle    starter-spring-boot/build.gradle
COPY service/build.gradle                service/build.gradle

RUN chmod +x gradlew

# Resolve dependencies before copying source (better layer cache — rebuilds only when source changes)
RUN ./gradlew dependencies --no-daemon -q 2>/dev/null || true

# Copy source
COPY sdk-java/src               sdk-java/src
COPY starter-spring-boot/src    starter-spring-boot/src
COPY service/src                service/src

# Build the service JAR, skip tests (tests run in CI)
RUN ./gradlew :atlas-flag-service:build -x test --no-daemon

# ── Runtime stage ──────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-jammy AS runtime

# Run as non-root
RUN groupadd -r atlasflag && useradd -r -g atlasflag atlasflag

WORKDIR /app

COPY --from=builder /app/service/build/libs/atlas-flag-service-1.0.0-SNAPSHOT.jar app.jar

RUN chown atlasflag:atlasflag app.jar

USER atlasflag

EXPOSE 8080

# Tuned for Render free tier (512 MB RAM). Override via JAVA_OPTS env var.
ENV JAVA_OPTS="-Xmx400m -Xms200m -XX:+UseSerialGC -XX:MaxMetaspaceSize=128m"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
