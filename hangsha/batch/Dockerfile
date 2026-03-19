FROM mcr.microsoft.com/playwright/java:v1.57.0-noble

WORKDIR /app

ENV TZ=Asia/Seoul

ARG JAR_FILE=batch/build/libs/*.jar
COPY ${JAR_FILE} app.jar

ENTRYPOINT ["java", "-Duser.timezone=Asia/Seoul", "-jar", "/app/app.jar"]