FROM maven:3.9.6-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY pom.xml .
COPY common common
COPY discovery-server discovery-server
COPY api-gateway api-gateway
COPY auth-service auth-service
COPY product-service product-service
COPY order-service order-service
COPY payment-service payment-service
COPY notification-service notification-service
RUN mvn -pl product-service -am -q -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /workspace/product-service/target/product-service-*.jar app.jar
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "app.jar"]
