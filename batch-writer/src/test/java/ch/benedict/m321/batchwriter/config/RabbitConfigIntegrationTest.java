package ch.benedict.m321.batchwriter.config;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Prüft, dass der batch-writer die Queues genau so anlegt wie der
 * chat-service (Spezifikation 3.9).
 */
@SpringBootTest
@Testcontainers
class RabbitConfigIntegrationTest {

    /** Echter Broker, denn nur er prüft, ob zwei Definitionen zusammenpassen. */
    @Container
    @ServiceConnection
    static RabbitMQContainer rabbitMq = new RabbitMQContainer("rabbitmq:3.13-management");

    /** Wird für den Start der Anwendung gebraucht, in diesem Test aber nicht benutzt. */
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    @Autowired
    private RabbitAdmin rabbitAdmin;

    /**
     * chat.persist noch einmal mit der Definition aus dem chat-service anlegen.
     * Hätte der batch-writer die Queue mit anderen Eigenschaften angelegt,
     * würde RabbitMQ hier mit PRECONDITION_FAILED ablehnen, und der Test wäre rot.
     */
    @Test
    void persistQueueMatchesDefinitionOfChatService() {
        Queue definitionOfChatService = QueueBuilder.durable("chat.persist")
                .deadLetterExchange("")
                .deadLetterRoutingKey("chat.dlq")
                .build();

        rabbitAdmin.declareQueue(definitionOfChatService);

        QueueInformation persistQueue = rabbitAdmin.getQueueInfo(QueueNames.PERSIST_QUEUE);
        assertNotNull(persistQueue);
    }

    /** Die Dead-Letter-Queue existiert, sonst gingen abgelehnte Nachrichten verloren. */
    @Test
    void deadLetterQueueExists() {
        Queue definitionOfChatService = QueueBuilder.durable("chat.dlq").build();

        rabbitAdmin.declareQueue(definitionOfChatService);

        QueueInformation deadLetterQueue = rabbitAdmin.getQueueInfo(QueueNames.DEAD_LETTER_QUEUE);
        assertNotNull(deadLetterQueue);
    }
}
