# Setup Instructions

## Java Version Requirements

**IMPORTANT:** This project requires **Java 17 or higher** (Java 21 recommended).

### Check Your Java Version

```bash
java -version
```

You should see version 17 or higher. If you see Java 8 or lower, you need to install a newer version.

### Setting JAVA_HOME

#### Windows

1. Download and install Java 17 or 21 from [Adoptium](https://adoptium.net/) or [Oracle](https://www.oracle.com/java/technologies/downloads/)

2. Set JAVA_HOME environment variable:
   ```powershell
   # PowerShell (temporary for current session)
   $env:JAVA_HOME = "C:\Program Files\Java\jdk-21"
   
   # Or set permanently:
   [System.Environment]::SetEnvironmentVariable('JAVA_HOME', 'C:\Program Files\Java\jdk-21', 'User')
   ```

3. Verify:
   ```powershell
   $env:JAVA_HOME
   java -version
   ```

#### Linux/Mac

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
export PATH=$JAVA_HOME/bin:$PATH
```

### Verify Gradle Can See Java

After setting JAVA_HOME, verify Gradle can use it:

```bash
./gradlew --version
```

You should see Java 17 or higher in the output.

## Common Issues

### Error: "Could not resolve org.springframework.boot:spring-boot-gradle-plugin"
**Cause:** Gradle is running with Java 8, but Spring Boot 3.2 requires Java 17+.

**Solution:** Set JAVA_HOME to Java 17 or higher (see above).

### Error: "Could not find org.flywaydb:flyway-database-postgresql"
**Cause:** Usually related to Java version issue preventing Spring Boot dependency management from working.

**Solution:** Ensure JAVA_HOME points to Java 17+ and rebuild.

### Error: "Could not reserve enough space for object heap"
**Cause:** Not enough memory allocated to Gradle.

**Solution:** The `gradle.properties` file is already configured with `-Xmx1024m`. If you still have issues, reduce it further or increase your system memory.

## Quick Setup Checklist

- [ ] Java 17+ installed
- [ ] JAVA_HOME environment variable set
- [ ] `java -version` shows 17 or higher
- [ ] `./gradlew --version` shows Java 17+ in output
- [ ] Docker and Docker Compose installed (for infrastructure)
