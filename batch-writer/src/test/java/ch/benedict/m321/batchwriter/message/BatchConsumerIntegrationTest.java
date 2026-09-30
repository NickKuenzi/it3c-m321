package ch.benedict.m321.batchwriter.message;

import ch.benedict.m321.batchwriter.config.QueueNames;
import org.junit.jupiter.api.BeforeEach;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Prüft den ganzen Weg: Nachricht in chat.persist legen, und sie steht danach
 * in der Tabelle. Mit echtem RabbitMQ und echter PostgreSQL.
 *
 * Die Nachrichten werden ROH gesendet: JSON-Bytes, nur mit content_type und
 * ohne __TypeId__. Genau so legt das Prüfskript in Szenario S5 seine
 * Nachrichten in die Queue.
 */
@SpringBootTest
@Testcontainers
class BatchConsumerIntegrationTest {

    /** Echter Broker, damit Stapel, Bestätigung und Dead Letter wirklich passieren. */
    @Container
    @ServiceConnection
    static RabbitMQContainer rabbitMq = new RabbitMQContainer("rabbitmq:3.13-management");

    /** Echte Datenbank, damit ON CONFLICT wirklich von PostgreSQL ausgewertet wird. */
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    private static final UUID ROOM_ID = UUID.fromString("5e2a9c10-1111-4d7e-8a3b-2c4d6e8f0a1b");

    /** Aufbau einer Nachricht wie vom chat-service. %s wird durch id und Text ersetzt. */
    private static final String MESSAGE_TEMPLATE = """
            {"id":"%s","roomId":"%s","senderId":"anna","senderName":"Anna Muster",\
            "content":"%s","sentAt":"2026-09-28T13:39:00Z"}""";

    /** Wie lange höchstens auf den batch-writer gewartet wird, in Zehntelsekunden. */
    private static final int MAX_WAIT_TENTHS = 300;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private RabbitAdmin rabbitAdmin;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Queues und Tabelle überleben die einzelne Testmethode. Ohne Leeren würde
     * ein Test die Nachrichten des vorherigen mitzählen.
     */
    @BeforeEach
    void emptyQueuesAndTable() {
        rabbitAdmin.purgeQueue(QueueNames.PERSIST_QUEUE);
        rabbitAdmin.purgeQueue(QueueNames.DEAD_LETTER_QUEUE);
        jdbcTemplate.update("DELETE FROM message");
    }

    /** Eine Nachricht ohne __TypeId__ wird gelesen und mit allen Werten gespeichert. */
    @Test
    void writesMessageSentWithoutTypeHeader() throws InterruptedException {
        UUID id = UUID.randomUUID();
        String json = toJson(id, "Hallo zusammen");

        sendRaw(json);

        waitForRowCount(1);
        String sql = "SELECT content FROM message WHERE id = ?";
        String storedContent = jdbcTemplate.queryForObject(sql, String.class, id);
        assertEquals("Hallo zusammen", storedContent);
    }

    /**
     * Szenario S5: dieselbe Nachricht zweimal. Danach steht sie genau einmal in
     * der Tabelle, und nichts liegt in chat.dlq.
     *
     * Dahinter kommt eine Markierung. Die Queue liefert der Reihe nach aus.
     * Steht die Markierung in der Tabelle, sind die beiden Exemplare davor also
     * sicher schon verarbeitet, und wir prüfen nicht zu früh.
     */
    @Test
    void duplicateGivesOneRowAndNothingInDeadLetterQueue() throws InterruptedException {
        UUID duplicateId = UUID.randomUUID();
        String duplicateJson = toJson(duplicateId, "S5-duplikat");
        UUID markerId = UUID.randomUUID();
        String markerJson = toJson(markerId, "Markierung");

        sendRaw(duplicateJson);
        sendRaw(duplicateJson);
        sendRaw(markerJson);

        // Zwei Zeilen: das Duplikat einmal, die Markierung einmal.
        waitForRowCount(2);
        int duplicateRows = countRowsWithId(duplicateId);
        int deadLetters = messageCountOf(QueueNames.DEAD_LETTER_QUEUE);
        assertEquals(1, duplicateRows);
        assertEquals(0, deadLetters);
    }

    /**
     * Kaputtes JSON geht nach chat.dlq, und die gültige Nachricht daneben wird
     * trotzdem geschrieben (Spezifikation 3.6).
     */
    @Test
    void invalidMessageGoesToDeadLetterQueueAndValidOneIsWritten() throws InterruptedException {
        UUID validId = UUID.randomUUID();
        String validJson = toJson(validId, "gueltig");

        sendRaw("Hallo, ich bin kein JSON");
        sendRaw(validJson);

        waitForRowCount(1);
        waitForMessageCount(QueueNames.DEAD_LETTER_QUEUE, 1);
        int validRows = countRowsWithId(validId);
        assertEquals(1, validRows);
    }

    /**
     * Viele Nachrichten, also mehrere volle Stapel: Alle landen in der Tabelle,
     * chat.persist ist danach leer, und genau ein Verbraucher hängt an der Queue.
     */
    @Test
    void writesManyMessagesAndLeavesQueueEmpty() throws InterruptedException {
        int messageCount = 1200;
        for (int i = 0; i < messageCount; i++) {
            UUID id = UUID.randomUUID();
            String json = toJson(id, "Nachricht " + i);
            sendRaw(json);
        }

        waitForRowCount(messageCount);
        waitForMessageCount(QueueNames.PERSIST_QUEUE, 0);
        QueueInformation persistQueue = rabbitAdmin.getQueueInfo(QueueNames.PERSIST_QUEUE);
        assertEquals(1, persistQueue.getConsumerCount());
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
     * Wartet, bis die Tabelle genau so viele Zeilen hat. Der batch-writer
     * arbeitet in einem eigenen Thread, das Ergebnis kommt also etwas später.
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

    /** Wartet, bis eine Queue genau so viele wartende Nachrichten hat. */
    private void waitForMessageCount(String queueName, int expected) throws InterruptedException {
        for (int i = 0; i < MAX_WAIT_TENTHS; i++) {
            int messages = messageCountOf(queueName);
            if (messages == expected) {
                return;
            }
            Thread.sleep(100);
        }
        int messagesAfterWaiting = messageCountOf(queueName);
        assertEquals(expected, messagesAfterWaiting, "messages in " + queueName + " after waiting");
    }

    /** Zählt alle Zeilen der Tabelle message. */
    private int countRows() {
        Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM message", Integer.class);
        return count;
    }

    /** Zählt die Zeilen mit einer bestimmten id. Mehr als 1 wäre ein Duplikat. */
    private int countRowsWithId(UUID id) {
        String sql = "SELECT count(*) FROM message WHERE id = ?";
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, id);
        return count;
    }

    /** Wie viele Nachrichten in einer Queue auf Abholung warten. */
    private int messageCountOf(String queueName) {
        QueueInformation queue = rabbitAdmin.getQueueInfo(queueName);
        return queue.getMessageCount();
    }
}
