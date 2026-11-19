FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY . .
RUN --mount=type=cache,target=/root/.m2,sharing=locked ./mvnw -B -pl tenant-service -am package -DskipTests
FROM eclipse-temurin:21-jre-jammy AS extract
WORKDIR /workspace
COPY --from=build /workspace/tenant-service/target/tenant-service-0.1.0-SNAPSHOT.jar app.jar
RUN mkdir /layers && java -Djarmode=tools -jar app.jar extract --layers --destination /layers
FROM eclipse-temurin:21-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* && useradd --system --uid 10001 appuser
WORKDIR /app
COPY --from=extract --chown=10001:10001 /layers/dependencies/ ./
COPY --from=extract --chown=10001:10001 /layers/snapshot-dependencies/ ./
COPY --from=extract --chown=10001:10001 /layers/application/ ./
USER 10001
EXPOSE 8082
HEALTHCHECK --interval=15s --timeout=3s --retries=10 CMD curl -fsS http://localhost:8082/actuator/health || exit 1
ENTRYPOINT ["java", "-cp", "/app/*:/app/lib/*", "com.hotelsaas.tenantservice.TenantserviceApplication"]
