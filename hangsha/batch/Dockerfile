FROM gradle:8.10-jdk17 AS builder
WORKDIR /app

COPY . .
RUN gradle :batch:bootJar --no-daemon

FROM eclipse-temurin:17-jre
WORKDIR /app

COPY --from=builder /app/batch/build/libs/*.jar /app/hangsha-batch.jar

ENTRYPOINT ["java", "-jar", "/app/hangsha-batch.jar"]