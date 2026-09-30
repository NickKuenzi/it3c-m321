package ch.benedict.m321.batchwriter.message;

import ch.benedict.m321.batchwriter.config.QueueNames;
import com.github.dockerjava.api.DockerClient;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Szenario S7 im Kleinen: Die Datenbank fällt aus, während Nachrichten kommen.
 * Danach muss alles in der Tabelle stehen, ohne dass jemand den batch-writer
 * neu startet, und nichts darf in chat.dlq gelandet sein (Spezifikation 3.5).
 *
 * Der PostgreSQL-Container wird angehalten (docker pause), nicht gestoppt.
 * Ein gestoppter Container bekäme beim Neustart einen anderen Port, und die
 * Anwendung fände ihn nicht mehr. Angehalten antwortet er einfach nicht, genau
 * wie eine Datenbank, die weg ist.
 */
@SpringBootTest
@Testcontainers
class DatabaseOutageIntegrationTest {

    /** Echter Broker, damit die Nachrichten wirklich unbestätigt liegen bleiben. */
    @Container
    @ServiceConnection
    static RabbitMQContainer rabbitMq = new RabbitMQContainer("rabbitmq:3.13-management");

    /** Echte Datenbank, die wir im Test anhalten und wieder laufen lassen. */
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    private static final UUID ROOM_ID = UUID.fromString("5e2a9c10-1111-4d7e-8a3b-2c4d6e8f0a1b");

    /** Aufbau einer Nachricht wie vom chat-service. %s wird durch id und Text ersetzt. */
    private static final String MESSAGE_TEMPLATE = """
            {"id":"%s","roomId":"%s","senderId":"anna","senderName":"Anna Muster",\
            "content":"%s","sentAt":"2026-09-28T13:39:00Z"}""";

    /** So lange ist die Datenbank im Test weg. */
    private static final long OUTAGE_MS = 8000;

    /** Wie lange nach dem Ausfall höchstens gewartet wird, in Zehntelsekunden. */
    private static final int MAX_WAIT_TENTHS = 600;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private RabbitAdmin rabbitAdmin;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Datenbank anhalten, 20 Nachrichten senden, 8 s warten, Datenbank wieder
     * laufen lassen. Danach stehen alle 20 in der Tabelle.
     *
     * Während des Ausfalls prüfen wir chat.dlq: leer. Die Nachrichten warten,
     * sie werden nicht aussortiert. Die Tabelle fragen wir in dieser Zeit
     * nicht ab, die Abfrage würde ja selbst hängen.
     */
    @Test
    void writesAllMessagesAfterDatabaseIsBack() throws InterruptedException {
        int messageCount = 20;

        pauseDatabase();
        try {
            for (int i = 0; i < messageCount; i++) {
                UUID id = UUID.randomUUID();
                String json = toJson(id, "Nachricht waehrend Ausfall " + i);
                sendRaw(json);
            }
            Thread.sleep(OUTAGE_MS);

            int deadLettersDuringOutage = messageCountOf(QueueNames.DEAD_LETTER_QUEUE);
            assertEquals(0, deadLettersDuringOutage, "messages in chat.dlq during outage");
        } finally {
            // Auch wenn oben etwas schiefgeht: Die Datenbank muss wieder laufen.
            unpauseDatabase();
        }

        waitForRowCount(messageCount);
        int deadLettersAfterwards = messageCountOf(QueueNames.DEAD_LETTER_QUEUE);
        assertEquals(0, deadLettersAfterwards, "messages in chat.dlq after outage");
    }

    /** Hält den PostgreSQL-Container an. Er läuft weiter, antwortet aber nicht mehr. */
    private void pauseDatabase() {
        DockerClient dockerClient = DockerClientFactory.instance().client();
        String containerId = postgres.getContainerId();
        dockerClient.pauseContainerCmd(containerId).exec();
    }

    /** Lässt den angehaltenen PostgreSQL-Container weiterlaufen. */
    private void unpauseDatabase() {
        DockerClient dockerClient = DockerClientFactory.instance().client();
        String containerId = postgres.getContainerId();
        dockerClient.unpauseContainerCmd(containerId).exec();
    }

    /**
     * Legt JSON-Bytes direkt in chat.persist, nur mit content_type. Ohne
     * Konverter, damit auch kein __TypeId__ mitkommt.
     */
    private void sendRaw(String json) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        Message message = new Message(body, properties);

        // Standard-Exchange "" mit dem Queue-Namen als Routing-Key: direkt in die Queue.
        rabbitTemplate.send("", QueueNames.PERSIST_QUEUE, message);
    }

    /** Baut das JSON einer Nachricht im Format des chat-service. */
    private String toJson(UUID id, String content) {
        return MESSAGE_TEMPLATE.formatted(id, ROOM_ID, content);
    }

    /**
     * Wartet, bis die Tabelle genau so viele Zeilen hat. Nach dem Ausfall
     * braucht der batch-writer einen Moment, bis sein nächster Versuch kommt.
     */
    private void waitForRowCount(int expected) throws InterruptedException {
        for (int i = 0; i < MAX_WAIT_TENTHS; i++) {
            int rows = countRows();
            if (rows == expected) {
                return;
            }
            Thread.sleep(100);
        }
        int rowsAfterWaiting = countRows();
        assertEquals(expected, rowsAfterWaiting, "rows after waiting");
    }

    /** Zählt alle Zeilen der Tabelle message. */
    private int countRows() {
        Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM message", Integer.class);
        return count;
    }

    /** Wie viele Nachrichten in einer Queue auf Abholung warten. */
    private int messageCountOf(String queueName) {
        QueueInformation queue = rabbitAdmin.getQueueInfo(queueName);
        return queue.getMessageCount();
    }
}
