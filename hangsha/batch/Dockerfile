# syntax=docker/dockerfile:1.7

FROM eclipse-temurin:17-jre-noble AS playwright-cli

COPY batch/build/playwright-cli /playwright-cli

FROM eclipse-temurin:17-jre-noble

WORKDIR /app

ENV TZ=Asia/Seoul
ENV PLAYWRIGHT_BROWSERS_PATH=/ms-playwright
ENV PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1

RUN --mount=type=bind,from=playwright-cli,source=/playwright-cli,target=/tmp/playwright-cli,ro \
    java -cp "/tmp/playwright-cli/*" com.microsoft.playwright.CLI install --with-deps chromium \
    && rm -rf /var/lib/apt/lists/*

ARG JAR_FILE=batch/build/libs/batch.jar
COPY ${JAR_FILE} app.jar

ENTRYPOINT ["java", "-Duser.timezone=Asia/Seoul", "-jar", "/app/app.jar"]
