FROM eclipse-temurin:17-jdk-alpine

WORKDIR /app

# 컨테이너 기본 타임존을 KST로 맞추기
RUN apk add --no-cache tzdata \
    && cp /usr/share/zoneinfo/Asia/Seoul /etc/localtime \
    && echo "Asia/Seoul" > /etc/timezone

ENV TZ=Asia/Seoul

ARG JAR_FILE=build/libs/*.jar
COPY ${JAR_FILE} app.jar

ENTRYPOINT ["java", "-Duser.timezone=Asia/Seoul", "-jar", "/app/app.jar"]