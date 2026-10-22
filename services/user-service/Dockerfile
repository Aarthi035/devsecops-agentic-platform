# ---------------------------------------------------------------------------
# Stage 1 - build. Maven + JDK compile and package the jar.
# Nothing from this stage survives into the final image.
# ---------------------------------------------------------------------------
FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /workspace

# Copy the pom first: the dependency download stays cached until the pom changes.
COPY pom.xml ./
RUN mvn -B -ntp dependency:go-offline

COPY src ./src
RUN mvn -B -ntp clean package -Dmaven.test.skip=true

# ---------------------------------------------------------------------------
# Stage 2 - runtime. JRE only (no Maven, no sources, no build tools), and a
# non-root user. Tag is pinned so the base image does not drift under us.
# ---------------------------------------------------------------------------
# The Ubuntu (glibc) JRE variant, not the Alpine (musl) one: the alpine tag
# fails to exec on this build host, while jammy runs everywhere.
FROM eclipse-temurin:17-jre-jammy AS runtime

# Unprivileged account, uid/gid 1001. The container never runs as root.
RUN groupadd --gid 1001 app \
 && useradd --uid 1001 --gid app --home-dir /app --create-home \
      --shell /usr/sbin/nologin app

WORKDIR /app
COPY --from=build --chown=1001:1001 /workspace/target/user-service-1.0.0.jar ./app.jar

USER 1001:1001
EXPOSE 8081

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

# Readiness comes from Spring Boot Actuator (management.endpoint.health.probes).
HEALTHCHECK --interval=15s --timeout=3s --start-period=45s --retries=5 \
  CMD wget -q -O - http://127.0.0.1:8081/actuator/health/readiness || exit 1

# Shell form + exec keeps java as PID 1 so SIGTERM reaches it (graceful shutdown).
ENTRYPOINT ["/bin/sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
