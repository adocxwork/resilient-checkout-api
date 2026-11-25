FROM maven:3.9.12-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY src/main ./src/main
RUN mvn -B -ntp -Dmaven.test.skip=true package

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /build/target/checkout-api.jar ./checkout-api.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/checkout-api.jar"]
