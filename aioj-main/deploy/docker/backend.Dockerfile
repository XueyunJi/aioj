FROM maven:3.9.9-eclipse-temurin-17@sha256:82e47241881f23ad774f5db8829efca15758e8fdb5b1d64ea9f8d6420a85068e AS build
ARG SERVICE_MODULE
WORKDIR /workspace
COPY backend/ ./
RUN mvn -pl ${SERVICE_MODULE} -am -DskipTests package

FROM eclipse-temurin:17-jre@sha256:c2e4377dff03f6d832095b8a4bcc7ed774c2e155c22f7d1f400b69897bed25d2
ARG SERVICE_MODULE
ENV JAVA_OPTS="-XX:InitialRAMPercentage=15 -XX:MaxRAMPercentage=50 -XX:+ExitOnOutOfMemoryError"
WORKDIR /app
RUN apt-get update -o Acquire::Retries=3 \
    && apt-get install -y --no-install-recommends -o Acquire::Retries=3 curl \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /workspace/${SERVICE_MODULE}/target/*.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
