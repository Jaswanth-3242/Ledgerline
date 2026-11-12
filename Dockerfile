FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY backend/ledgerline/pom.xml .
COPY backend/ledgerline/src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
ENV JAVA_OPTS="-Xmx350m"
EXPOSE 8080
CMD ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
