# syntax=docker/dockerfile:1

FROM eclipse-temurin:24-jdk AS builder
WORKDIR /workspace

COPY gradlew gradlew
COPY gradle/wrapper/gradle-wrapper.jar gradle/wrapper/gradle-wrapper.jar
COPY gradle/wrapper/gradle-wrapper.properties gradle/wrapper/gradle-wrapper.properties
COPY build.gradle.kts settings.gradle.kts ./
COPY src src

RUN chmod +x ./gradlew
RUN ./gradlew --no-daemon clean bootJar
RUN JAR_PATH=$(find build/libs -maxdepth 1 -type f -name "*-SNAPSHOT.jar" ! -name "*-plain.jar" | head -n 1) \
    && test -n "$JAR_PATH" \
    && cp "$JAR_PATH" app.jar

FROM eclipse-temurin:24-jre
WORKDIR /app

# Install network utilities for debugging
RUN apt-get update && apt-get install -y --no-install-recommends \
    iputils-ping \
    curl \
    wget \
    net-tools \
    dnsutils \
    && rm -rf /var/lib/apt/lists/*

RUN useradd --system --uid 10001 spring
USER spring

COPY --from=builder /workspace/app.jar app.jar

EXPOSE 7000
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

