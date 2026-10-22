# syntax=docker/dockerfile:1.7
# Build context: api/.   docker buildx build --platform linux/amd64,linux/arm64 -f api/Dockerfile api
# Tests are skipped here on purpose: CI (.github/workflows/api.yml) runs `./mvnw verify`.
# No configuration is baked in: pass SPRING_PROFILES_ACTIVE and DB_* / NEON_DATABASE_URL at runtime.

FROM --platform=$BUILDPLATFORM eclipse-temurin:21.0.8_9-jdk-jammy AS build
WORKDIR /src
# unzip: without it the Maven wrapper fetches the .tar.gz, whose checksum differs from the pinned .zip one.
RUN apt-get update && apt-get install -y --no-install-recommends unzip && rm -rf /var/lib/apt/lists/*
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q dependency:go-offline
COPY HealthCheck.java ./
RUN javac --release 21 -d /hc HealthCheck.java
COPY src/ src/
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q package -DskipTests \
    && mv target/finpath-api-*.jar target/app.jar \
    && java -Djarmode=tools -jar target/app.jar extract --layers --destination /layers

FROM eclipse-temurin:21.0.8_9-jre-jammy AS runtime
RUN groupadd --system --gid 10001 app && useradd --system --uid 10001 --gid app --no-create-home --shell /usr/sbin/nologin app
WORKDIR /app
# Layers ordered least- to most-frequently changing so rebuilds reuse the big dependency layer.
COPY --from=build --chown=app:app /layers/dependencies/ ./
COPY --from=build --chown=app:app /layers/snapshot-dependencies/ ./
COPY --from=build --chown=app:app /layers/application/ ./
COPY --from=build --chown=app:app /hc/ /app/hc/
USER app
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError" \
    SPRING_PROFILES_ACTIVE=prod
EXPOSE 8080
# No curl/wget in the image: a tiny precompiled java client (HealthCheck.java, compiled in the build stage because the JRE has no compiler) probes /actuator/health/liveness (not /actuator/health, which queries the DB and would defeat Neon scale-to-zero).
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
    CMD ["java", "-Xmx32m", "-Xss256k", "-Xshare:auto", "-XX:TieredStopAtLevel=1", "-cp", "/app/hc", "HealthCheck"]
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
