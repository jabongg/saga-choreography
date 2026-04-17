# syntax=docker/dockerfile:1.7

# -------- Build Stage --------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY . .

RUN --mount=type=cache,target=/root/.m2/repository \
    ./mvnw -q -pl api-gateway -am -DskipTests package

# -------- Runtime Stage --------
FROM eclipse-temurin:21-jre
WORKDIR /app

COPY --from=build /workspace/api-gateway/target/api-gateway-0.0.1-SNAPSHOT.jar app.jar

EXPOSE 8085

ENTRYPOINT ["java","-jar","/app/app.jar"]
