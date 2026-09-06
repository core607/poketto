# syntax=docker/dockerfile:1

# Build stage: the Gradle build runs on the same JDK the toolchain requires. Tests are not run
# here; `check` verifies the commit before an image is published.
FROM eclipse-temurin:26-jdk-noble@sha256:091b6640864939942cd9d7ddd16576f31112d5a56a595b566ce561d1c0e07c6b AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
RUN --mount=type=cache,target=/root/.gradle chmod +x gradlew && ./gradlew --no-daemon --version >/dev/null
COPY src ./src
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon --quiet bootJar \
    && java -Djarmode=tools -jar build/libs/poketto-*.jar extract --layers --launcher --destination /app

# Runtime stage: JRE only, a dedicated non-root user, and curl for the Compose health check.
# The image carries no configuration, credential, content repository, or data directory.
FROM eclipse-temurin:26-jre-noble@sha256:c12a27c567c4ce00b0caef14900c1bf2f5e997524c3ec73463ae695352d5f34d
# deploy.sh verifies this label against the commit it was asked to deploy.
ARG POKETTO_REVISION
LABEL org.opencontainers.image.revision="${POKETTO_REVISION}" \
      org.opencontainers.image.source="https://github.com/core607/poketto"
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 10001 poketto \
    && useradd --system --uid 10001 --gid 10001 --home-dir /var/lib/poketto --create-home poketto
WORKDIR /app
COPY --from=build /app/dependencies/ ./
COPY --from=build /app/spring-boot-loader/ ./
COPY --from=build /app/snapshot-dependencies/ ./
COPY --from=build /app/application/ ./
USER poketto
ENV POKETTO_DATA_DIR=/var/lib/poketto
EXPOSE 8080
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
