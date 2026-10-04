ARG MODULE

FROM maven:3.9.16-eclipse-temurin-17 AS build
ARG MODULE
WORKDIR /workspace
COPY . .
RUN case "${MODULE}" in \
      prizeflux-campaign|prizeflux-worker|prizeflux-ai) ;; \
      *) echo "Unsupported PrizeFlux module" >&2; exit 2 ;; \
    esac \
    && mvn --batch-mode --no-transfer-progress \
      --projects "${MODULE}" --also-make -DskipTests package

FROM eclipse-temurin:17.0.20.1_1-jre-jammy AS runtime
ARG MODULE
WORKDIR /app
COPY --from=build --chown=10001:10001 \
  /workspace/${MODULE}/target/${MODULE}-0.1.0-SNAPSHOT.jar /app/app.jar
USER 10001:10001
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/app.jar"]
