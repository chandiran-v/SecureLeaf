# ── Backend Dockerfile ─────────────────────────────────────────────────────
# Multi-stage build: compile with Maven, run with slim JRE image

# Stage 1 — Build
FROM maven:3.9.6-eclipse-temurin-21 AS builder

WORKDIR /app

# Cache dependencies layer separately
COPY pom.xml .
RUN mvn dependency:go-offline -B

COPY src ./src
RUN mvn package -DskipTests -B

# Stage 2 — Runtime
FROM eclipse-temurin:21-jre-jammy AS runtime

WORKDIR /app

# Create non-root user for security
RUN addgroup --system secureleaf && adduser --system --ingroup secureleaf secureleaf
USER secureleaf

COPY --from=builder --chown=secureleaf:secureleaf /app/target/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-XX:+ExitOnOutOfMemoryError", \
  "-jar", "app.jar"]
