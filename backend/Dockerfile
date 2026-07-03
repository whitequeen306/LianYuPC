# JDK 17 — 与 backend/pom.xml java.version 一致
# 构建: docker compose --profile full build backend
# 或:   docker build -t lianyu-pc/backend:dev ./backend

FROM maven:3.9-eclipse-temurin-17-alpine AS build
WORKDIR /build

# 依赖层（仅 pom 变更时重建）
COPY pom.xml .
COPY lianyu-common/pom.xml lianyu-common/pom.xml
COPY lianyu-dao/pom.xml lianyu-dao/pom.xml
COPY lianyu-storage/pom.xml lianyu-storage/pom.xml
COPY lianyu-security/pom.xml lianyu-security/pom.xml
COPY lianyu-ai/pom.xml lianyu-ai/pom.xml
COPY lianyu-service/pom.xml lianyu-service/pom.xml
COPY lianyu-web/pom.xml lianyu-web/pom.xml
COPY lianyu-app/pom.xml lianyu-app/pom.xml
COPY lianyu-qq-bridge/pom.xml lianyu-qq-bridge/pom.xml
COPY settings-docker.xml /root/.m2/settings.xml
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B dependency:go-offline -q

# 源码层（任意模块 src 变更即重新编译打包）
COPY lianyu-common/src lianyu-common/src
COPY lianyu-dao/src lianyu-dao/src
COPY lianyu-storage/src lianyu-storage/src
COPY lianyu-security/src lianyu-security/src
COPY lianyu-ai/src lianyu-ai/src
COPY lianyu-service/src lianyu-service/src
COPY lianyu-web/src lianyu-web/src
COPY lianyu-app/src lianyu-app/src
COPY lianyu-qq-bridge/src lianyu-qq-bridge/src

ARG APP_REVISION=dev
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B clean package -DskipTests -q

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

ARG APP_REVISION=dev
LABEL org.opencontainers.image.revision="${APP_REVISION}"

COPY --from=build /build/lianyu-app/target/lianyu-app-*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
