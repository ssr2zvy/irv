FROM node:22-bookworm-slim AS ui
WORKDIR /build/frontend
COPY frontend/package*.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm run build
FROM maven:3.9-eclipse-temurin-26 AS backend
WORKDIR /build
COPY backend/pom.xml ./pom.xml
RUN mvn -q dependency:go-offline
COPY backend/src ./src
COPY --from=ui /build/frontend/dist ./src/main/resources/static
RUN mvn -q -DskipTests package
FROM eclipse-temurin:25-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends curl ca-certificates && rm -rf /var/lib/apt/lists/*
ENV FLYCTL_INSTALL=/opt/fly
RUN curl --fail --silent --show-error --location https://fly.io/install.sh -o /tmp/install-fly.sh && sh /tmp/install-fly.sh && rm /tmp/install-fly.sh
ENV FLYCTL_PATH=/opt/fly/bin/flyctl IRV_FLY_LOGS=true
RUN groupadd -g 10001 irv && useradd -u 10001 -g irv -m irv && mkdir /data && chown irv:irv /data
WORKDIR /app
COPY --from=backend /build/target/irv-0.1.0.jar /app/irv.jar
USER irv
ENV IRV_BLOB_DIR=/data/blobs
EXPOSE 8080
ENTRYPOINT ["java","-XX:MaxRAMPercentage=70","-jar","/app/irv.jar"]
