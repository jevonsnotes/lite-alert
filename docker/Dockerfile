# syntax=docker/dockerfile:1.7

# ---------- 1) build ----------
# Maven downloads Node into target/ via frontend-maven-plugin, so the build
# image only needs JDK + Maven; no extra Node install.
FROM maven:3.9-eclipse-temurin-17 AS api
WORKDIR /workspace

# Cache deps before copying source.
COPY pom.xml ./
COPY backend/pom.xml backend/pom.xml
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q -pl backend -am dependency:go-offline

# Backend source + frontend tree. backend/pom.xml runs npm install + build
# automatically; Vite emits to target/frontend-static and Maven copies it
# into target/classes/static for the Spring Boot classpath.
COPY backend/src backend/src
COPY frontend frontend

RUN --mount=type=cache,target=/root/.m2 \
    --mount=type=cache,target=/workspace/frontend/node_modules \
    mvn -B -q -pl backend -am package -DskipTests

# ---------- 2) runtime ----------
FROM eclipse-temurin:17-jre-alpine
LABEL org.opencontainers.image.title="lite-alert" \
      org.opencontainers.image.source="https://github.com/jevonsnotes/lite-alert"
ENV TZ=Asia/Shanghai \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -Djava.security.egd=file:/dev/./urandom"
RUN addgroup -S app && adduser -S app -G app && \
    mkdir -p /data /opt/lite-alert/config && \
    chown -R app:app /data /opt/lite-alert && \
    apk add --no-cache wget
WORKDIR /opt/lite-alert
COPY --from=api /workspace/backend/target/lite-alert.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["sh","-c","exec java $JAVA_OPTS -jar app.jar"]
