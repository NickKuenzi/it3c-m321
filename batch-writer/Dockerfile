# Stufe 1: bauen
# Der Build-Kontext ist das Projekt-Wurzelverzeichnis, weil das Modul
# das Eltern-POM braucht.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
# Das Eltern-POM nennt beide Module. Maven liest beim Bauen alle ein, auch
# wenn nur eines gebaut wird. Deshalb braucht es auch die pom.xml des
# chat-service, sonst bricht der Build mit "Child module ... does not exist" ab.
COPY chat-service/pom.xml chat-service/pom.xml
COPY batch-writer/pom.xml batch-writer/pom.xml
COPY batch-writer/src batch-writer/src
# Tests werden hier übersprungen: Testcontainers bräuchte einen Docker-Daemon
# INNERHALB des Builds. Getestet wird vorher mit "mvn test".
RUN mvn -q -pl batch-writer -am package -DskipTests

# Stufe 2: laufen
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/batch-writer/target/batch-writer-0.1.0-SNAPSHOT.jar app.jar
# Kein EXPOSE: Der batch-writer hat keine HTTP-Schnittstelle. Er holt seine
# Nachrichten selbst bei RabbitMQ ab und schreibt in PostgreSQL.
ENTRYPOINT ["java", "-jar", "app.jar"]
