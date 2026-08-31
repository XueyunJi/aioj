FROM eclipse-temurin:17-jre@sha256:c2e4377dff03f6d832095b8a4bcc7ed774c2e155c22f7d1f400b69897bed25d2

WORKDIR /app
ENV JAVA_OPTS="-XX:InitialRAMPercentage=15 -XX:MaxRAMPercentage=50 -XX:+ExitOnOutOfMemoryError"

RUN apt-get update -o Acquire::Retries=3 \
    && apt-get install -y --no-install-recommends -o Acquire::Retries=3 curl \
    && rm -rf /var/lib/apt/lists/*

COPY ai-service/target/*.jar /app/app.jar

EXPOSE 8204
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
