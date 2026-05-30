# Setup Instructions

## Java Version Requirements

This project requires **Java 21** (minimum Java 17).

```bash
java -version
# Should show: openjdk version "21.x.x" or "17.x.x"
```

If you see Java 8 or lower, install a newer version first.

## Install Java 21

Download from [Adoptium](https://adoptium.net/) — choose **Temurin 21 (LTS)**.

### Windows

1. Download and run the installer from Adoptium
2. Set `JAVA_HOME`:
   ```powershell
   # Temporary (current session)
   $env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21"

   # Permanent
   [System.Environment]::SetEnvironmentVariable('JAVA_HOME','C:\Program Files\Eclipse Adoptium\jdk-21','User')
   ```
3. Verify:
   ```powershell
   $env:JAVA_HOME
   java -version
   ```

### macOS

```bash
brew install --cask temurin@21
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
```

### Linux

```bash
sudo apt install temurin-21-jdk   # Ubuntu/Debian with Adoptium repo
export JAVA_HOME=/usr/lib/jvm/temurin-21
export PATH=$JAVA_HOME/bin:$PATH
```

## Verify Gradle Can See Java

```bash
./gradlew --version
# Output should include: JVM: 21.x.x
```

## Infrastructure (Local Dev)

Only PostgreSQL is required 

```bash
cd infra
docker-compose up -d
```

## Common Errors

| Error | Cause | Fix |
|---|---|---|
| `Could not resolve org.springframework.boot:spring-boot-gradle-plugin` | Gradle running on Java 8 | Set JAVA_HOME to Java 21 |
| `Could not find org.flywaydb:flyway-database-postgresql` | Same Java version issue | Set JAVA_HOME to Java 17+ |
| `Could not reserve enough space for object heap` | Low memory | `gradle.properties` sets `-Xmx1024m`; reduce if needed |
| `Connection refused localhost:5432` | PostgreSQL not running | `cd infra && docker-compose up -d` |

## Quick Setup Checklist

- [ ] Java 21 installed and `java -version` confirms it
- [ ] `JAVA_HOME` points to Java 21 directory
- [ ] `./gradlew --version` shows JVM 21
- [ ] Docker running — `docker ps` works
- [ ] `cd infra && docker-compose up -d` starts PostgreSQL
