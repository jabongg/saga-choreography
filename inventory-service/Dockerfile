# syntax=docker/dockerfile:1.7

# -------- Build Stage --------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY . .

RUN mvn -pl inventory-service -am -DskipTests package

# -------- Runtime Stage --------
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/inventory-service/target/*.jar app.jar
EXPOSE 8086
ENTRYPOINT ["java","-jar","/app/app.jar"]