FROM eclipse-temurin:25.0.5.1_2-jdk-noble AS build
WORKDIR /build
COPY .mvn .mvn
COPY mvnw pom.xml checkstyle.xml checkstyle-suppressions.xml ./
RUN chmod +x mvnw
COPY src src
RUN ./mvnw -B -DskipTests package

FROM eclipse-temurin:25.0.5.1_2-jre-noble
WORKDIR /app
RUN useradd --system --uid 10001 docukore
COPY --from=build /build/target/docukore-backend-0.1.0-SNAPSHOT.jar /app/docukore.jar
USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/docukore.jar"]
