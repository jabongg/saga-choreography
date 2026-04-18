# syntax=docker/dockerfile:1.7

# -------- Build Stage --------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY . .

RUN mvn -pl user-service -am -DskipTests package

# -------- Runtime Stage --------
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/user-service/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]