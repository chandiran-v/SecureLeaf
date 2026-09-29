# ── Backend Dockerfile ─────────────────────────────────────────────────────
# Multi-stage build: compile with Maven, run with a slim JRE image.
# Build context: backend/   (docker build -f infra/docker/backend.Dockerfile backend/)
# Multi-arch: eclipse-temurin and maven publish linux/amd64 and linux/arm64, so this builds
# unchanged on the Oracle Ampere (aarch64) server. Phase 09D D1/D6.

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

# Phase 09D D6 — FONTS. The watermark is drawn as *text* by Java2D, which needs system fonts and
# fontconfig. Slim images often ship none: the watermark then fails ("Fontconfig head is null")
# or draws nothing — only in production, never on a laptop. curl is for the HEALTHCHECK.
RUN apt-get update \
 && apt-get install -y --no-install-recommends fontconfig fonts-dejavu-core curl \
 && fc-cache -f \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Create non-root user for security
RUN addgroup --system secureleaf && adduser --system --ingroup secureleaf --home /home/secureleaf secureleaf
USER secureleaf

COPY --from=builder --chown=secureleaf:secureleaf /app/target/*.jar app.jar

EXPOSE 8080

# Phase 09D D2 — the JVM sizes its heap as a percentage of the CONTAINER memory limit
# (compose mem_limit), not of the host's RAM. 70% leaves room for metaspace, thread stacks and
# Java2D native buffers. ExitOnOutOfMemoryError lets Docker restart a wedged JVM.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError -Djava.awt.headless=true"

# Readiness = app started and Postgres/Redis reachable (application.yml management.group.readiness).
# Phase 10 D1: under the prod profile actuator listens on 8081 (management.server.port); with any
# other profile it shares 8080 — so try the management port first, then the app port.
HEALTHCHECK --interval=15s --timeout=5s --start-period=90s --retries=5 \
  CMD curl -fsS http://localhost:8081/actuator/health/readiness \
   || curl -fsS http://localhost:8080/actuator/health/readiness || exit 1

# `exec` so java is PID 1 and receives SIGTERM for a graceful shutdown.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
