# syntax=docker/dockerfile:1

FROM eclipse-temurin:25-jdk AS builder
WORKDIR /workspace

ENV GRADLE_USER_HOME=/home/gradle/.gradle

COPY gradlew gradlew
COPY gradle/wrapper/gradle-wrapper.jar gradle/wrapper/gradle-wrapper.jar
COPY gradle/wrapper/gradle-wrapper.properties gradle/wrapper/gradle-wrapper.properties
COPY build.gradle.kts settings.gradle.kts ./

RUN chmod +x ./gradlew
RUN --mount=type=cache,target=/home/gradle/.gradle ./gradlew --no-daemon dependencies

COPY src src

RUN --mount=type=cache,target=/home/gradle/.gradle ./gradlew --no-daemon clean bootJar
RUN JAR_PATH=$(find build/libs -maxdepth 1 -type f -name "*-SNAPSHOT.jar" ! -name "*-plain.jar" | head -n 1) \
    && test -n "$JAR_PATH" \
    && cp "$JAR_PATH" app.jar

FROM eclipse-temurin:25-jre-alpine
WORKDIR /app

RUN addgroup -S spring && adduser -S -u 10001 -G spring spring
USER spring

COPY --from=builder /workspace/app.jar app.jar

EXPOSE 7000
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

