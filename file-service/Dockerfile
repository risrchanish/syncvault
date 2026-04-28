FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

# Build and install ai-sdk (local SNAPSHOT dependency)
COPY ai-sdk/pom.xml ai-sdk/pom.xml
COPY ai-sdk/src ai-sdk/src
RUN mvn -f ai-sdk/pom.xml install -DskipTests -B

# Build file-service
COPY file-service/pom.xml file-service/pom.xml
RUN mvn -f file-service/pom.xml dependency:go-offline -B
COPY file-service/src file-service/src
RUN mvn -f file-service/pom.xml package -DskipTests -B

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /workspace/file-service/target/*.jar app.jar
ENTRYPOINT ["java", "-Djava.security.egd=file:/dev/./urandom", "-jar", "app.jar"]
