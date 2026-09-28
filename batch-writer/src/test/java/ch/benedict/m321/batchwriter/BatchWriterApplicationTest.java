package ch.benedict.m321.batchwriter;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Prüft, dass der batch-writer mit einem echten RabbitMQ und einer echten
 * PostgreSQL hochfährt.
 *
 * Beide kommen per Testcontainers. So läuft "mvn test" auch dann, wenn der
 * Stack aus docker-compose nicht gestartet ist (Szenario S1).
 */
@SpringBootTest
@Testcontainers
class BatchWriterApplicationTest {

    /** Echter Broker, dieselbe Version wie in docker-compose.yml. */
    @Container
    @ServiceConnection
    static RabbitMQContainer rabbitMq = new RabbitMQContainer("rabbitmq:3.13-management");

    /** Echte Datenbank, dieselbe Version wie später in docker-compose.yml. */
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    /** Der Test besteht darin, dass der Spring-Kontext ohne Fehler startet. */
    @Test
    void contextLoads() {
    }
}
