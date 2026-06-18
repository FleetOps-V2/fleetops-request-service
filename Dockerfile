FROM maven:3.9.6-eclipse-temurin-21-alpine AS build
WORKDIR /app
COPY pom.xml .
# Comment out to speed up build by not downloading all unused managed dependencies
# RUN mvn dependency:go-offline || true
COPY src ./src
RUN mvn clean package -DskipTests

FROM eclipse-temurin:21-jre-alpine
RUN apk upgrade --no-cache
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080

RUN addgroup -S spring && adduser -S spring -G spring
USER spring:spring

# FIX #14: JAVA_OPTS read from env (set per-service in docker-compose.yml)
# Defaults: 128MB initial, 384MB max — safe for a dev machine running all services
ENV JAVA_OPTS="-Xms128m -Xmx384m"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
