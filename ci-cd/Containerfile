FROM cloudflare/cloudflared:2026.10.0 AS tunnel
FROM node:22-bookworm-slim AS ui
WORKDIR /build/frontend
COPY frontend/package*.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm run build
FROM maven:3.9-eclipse-temurin-17 AS backend
WORKDIR /build
COPY backend/pom.xml ./pom.xml
RUN mvn -q dependency:go-offline
COPY backend/src ./src
COPY --from=ui /build/frontend/dist ./src/main/resources/static
RUN mvn -q -DskipTests package
FROM eclipse-temurin:17-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends curl ca-certificates openssl gosu python3 supervisor && rm -rf /var/lib/apt/lists/*
ENV FLYCTL_INSTALL=/opt/fly
RUN curl --fail --silent --show-error --location https://fly.io/install.sh -o /tmp/install-fly.sh && sh /tmp/install-fly.sh && rm /tmp/install-fly.sh
ENV FLYCTL_PATH=/opt/fly/bin/flyctl IRV_FLY_LOGS=true
RUN groupadd -g 10001 irv && useradd -u 10001 -g irv -m irv && mkdir /data && chown irv:irv /data
RUN groupadd -g 10002 cloudflared && useradd -u 10002 -g cloudflared -M -s /usr/sbin/nologin cloudflared
COPY --from=tunnel /usr/local/bin/cloudflared /usr/local/bin/cloudflared
WORKDIR /app
ARG BUILD_REVISION=local
ARG BUILD_ID=local
ARG BUILD_TIMESTAMP
LABEL org.opencontainers.image.revision="$BUILD_REVISION" org.opencontainers.image.version="$BUILD_ID" org.opencontainers.image.created="$BUILD_TIMESTAMP"
COPY --from=backend /build/target/irv-0.1.0.jar /app/irv.jar
COPY ci-cd/container-scripts/entrypoint.sh /usr/local/bin/irv-entrypoint
COPY ci-cd/container-scripts/start.py /usr/local/bin/irv-start
COPY ci-cd/container-scripts/wait-ready.py /usr/local/bin/irv-wait-ready
COPY ci-cd/container-scripts/fatal-exit.py /usr/local/lib/irv/fatal-exit.py
COPY ci-cd/container-scripts/supervisord.conf /etc/irv-supervisord.conf
RUN chmod 755 /usr/local/bin/irv-entrypoint /usr/local/bin/irv-start /usr/local/bin/irv-wait-ready
ENV IRV_BLOB_DIR=/data/blobs
EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/irv-start"]
CMD ["java","-XX:MaxRAMPercentage=70","-jar","/app/irv.jar"]
