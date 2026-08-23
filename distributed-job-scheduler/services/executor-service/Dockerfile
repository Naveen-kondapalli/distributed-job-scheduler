FROM eclipse-temurin:17-jdk-jammy AS build

WORKDIR /workspace
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -DskipTests dependency:go-offline
COPY src/ src/
RUN ./mvnw -DskipTests package

FROM eclipse-temurin:17-jre-jammy

WORKDIR /app
RUN groupadd --system spring && useradd --system --gid spring --home-dir /app spring
COPY --from=build /workspace/target/*.jar /app/app.jar
RUN chown -R spring:spring /app

USER spring:spring
EXPOSE 8082
ENV JAVA_TOOL_OPTIONS="-Duser.timezone=Asia/Kolkata"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
