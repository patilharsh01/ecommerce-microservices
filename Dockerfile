# syntax=docker/dockerfile:1.7
#
# One recipe for every Java service:
#   docker build --build-arg SERVICE=order-service -t ecommerce/order-service .
#
# The build stage does not depend on SERVICE, so when docker compose builds all seven images
# BuildKit runs the Maven build once and every image reuses it.

FROM maven:3.9.11-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY . .
RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    mvn -B -q -DskipTests package

# Split the fat jar into layers (dependencies change rarely, application code often),
# so rebuilding after a code change only ships the small top layer.
FROM eclipse-temurin:17-jre-alpine AS extract
ARG SERVICE
WORKDIR /extract
COPY --from=build /workspace/${SERVICE}/target/${SERVICE}-*.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --destination layers

FROM eclipse-temurin:17-jre-alpine
ARG SERVICE
LABEL org.opencontainers.image.title="${SERVICE}" \
      org.opencontainers.image.source="https://github.com/patilharsh01/ecommerce-microservices"
RUN addgroup -S app && adduser -S -G app app \
    && mkdir -p /app/data && chown app:app /app/data
WORKDIR /app
COPY --from=extract /extract/layers/dependencies/ ./
COPY --from=extract /extract/layers/spring-boot-loader/ ./
COPY --from=extract /extract/layers/snapshot-dependencies/ ./
COPY --from=extract /extract/layers/application/ ./
USER app
# Size the heap from the container's memory limit; crash fast (and get restarted) on OOM.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "-jar", "app.jar"]
