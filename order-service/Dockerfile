# syntax=docker/dockerfile:1.7
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY . .
RUN mvn -pl order-service -am -DskipTests package
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/order-service/target/*.jar app.jar
EXPOSE 8082
ENTRYPOINT ["java","-jar","/app/app.jar"]
