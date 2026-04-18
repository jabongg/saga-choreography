# syntax=docker/dockerfile:1.7

# -------- Build Stage --------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY . .

RUN mvn -pl api-gateway -am -DskipTests package

# -------- Runtime Stage --------
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/api-gateway/target/*.jar app.jar
EXPOSE 8085
ENTRYPOINT ["java","-jar","/app/app.jar"]
