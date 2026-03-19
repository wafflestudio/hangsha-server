FROM eclipse-temurin:17-jdk-alpine

WORKDIR /app

RUN apk add --no-cache tzdata \
    && cp /usr/share/zoneinfo/Asia/Seoul /etc/localtime \
    && echo "Asia/Seoul" > /etc/timezone

ENV TZ=Asia/Seoul

ARG JAR_FILE=batch/build/libs/*.jar
COPY ${JAR_FILE} app.jar

ENTRYPOINT ["java", "-Duser.timezone=Asia/Seoul", "-jar", "/app/app.jar"]