# Build context: project root
# docker build -f api-gateway/Dockerfile -t contextflow/api-gateway:latest .
FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /workspace

COPY pom.xml .
COPY auth-service/pom.xml auth-service/
COPY ingestion-service/pom.xml ingestion-service/
COPY query-service/pom.xml query-service/
COPY api-gateway/pom.xml api-gateway/

RUN --mount=type=cache,target=/root/.m2 \
    mvn -pl api-gateway -am dependency:go-offline -q

COPY api-gateway/src api-gateway/src

RUN --mount=type=cache,target=/root/.m2 \
    mvn -pl api-gateway -am -DskipTests package -q

# ── Runtime ──────────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-jammy
ARG OTEL_VERSION=2.4.0
ADD https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v${OTEL_VERSION}/opentelemetry-javaagent.jar /otel-javaagent.jar

RUN groupadd -r app && useradd -r -g app app
WORKDIR /app
COPY --from=builder /workspace/api-gateway/target/api-gateway-*-SNAPSHOT.jar app.jar
RUN chown -R app:app /app
USER app

EXPOSE 8080
ENTRYPOINT ["java", \
  "-javaagent:/otel-javaagent.jar", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-jar", "app.jar"]
