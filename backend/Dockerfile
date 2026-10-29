# Build context is the repository root: docker build -f backend/Dockerfile .
FROM gradle:8.14.3-jdk21 AS build
WORKDIR /src
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
COPY shared shared
COPY backend backend
RUN gradle :backend:installDist --no-daemon -q

FROM eclipse-temurin:21-jre
# deploy/deploy.sh passes the host service user's uid, so the container can read the 0600 Enable Banking
# key: Compose secrets outside Swarm are bind mounts that keep the host file's owner and mode.
ARG APP_UID=10001
# The Ubuntu base image ships an `ubuntu` user with uid 1000, the usual uid of the host's first user.
RUN existing="$(getent passwd "${APP_UID}" | cut -d: -f1)" \
    && if [ -n "$existing" ]; then userdel "$existing"; fi \
    && useradd --system --no-create-home --uid "${APP_UID}" app
WORKDIR /app
COPY --from=build /src/backend/build/install/backend /app
USER app
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/8080 && printf 'GET /health HTTP/1.0\\r\\n\\r\\n' >&3 && grep -q ok <&3"]
ENTRYPOINT ["/app/bin/backend"]
