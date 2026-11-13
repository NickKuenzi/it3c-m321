package ch.benedict.m321.chatservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Prüft, dass der Spring-Kontext überhaupt hochfährt.
 *
 * Seit der Live-Zustellung hört der Dienst beim Start an seiner Live-Queue.
 * Dafür braucht er einen RabbitMQ. Ohne eigenen Test-Broker würde der Test
 * sich mit dem verbinden, was zufällig auf localhost:5672 läuft, und je nach
 * Rechner grün oder rot sein. Darum bekommt er einen eigenen Container.
 */
@SpringBootTest
@Testcontainers
class ChatServiceApplicationTest {

    /** Eigener Broker, damit das Ergebnis nicht vom Rechner abhängt. */
    @Container
    @ServiceConnection
    static RabbitMQContainer rabbitMq = new RabbitMQContainer("rabbitmq:3.13-management");

    /** Der Kontext fährt mit allen Beans hoch, auch mit dem Verbraucher an der Live-Queue. */
    @Test
    void contextLoads() {
        // Kein Assert nötig. Fährt der Kontext nicht hoch, wirft Spring
        // eine Exception und der Test wird rot.
    }
}
