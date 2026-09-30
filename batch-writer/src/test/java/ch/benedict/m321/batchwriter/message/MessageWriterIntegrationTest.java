package ch.benedict.m321.batchwriter.message;

import ch.benedict.m321.batchwriter.dto.ChatMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Prüft den Schreibweg gegen eine echte PostgreSQL: ein Stapel wird
 * vollständig geschrieben, und Duplikate landen nicht doppelt in der Tabelle
 * (Spezifikation 3.1, 3.2, Szenario S5).
 */
@SpringBootTest
@Testcontainers
class MessageWriterIntegrationTest {

    /** Wird für den Start der Anwendung gebraucht, in diesem Test aber nicht benutzt. */
    @Container
    @ServiceConnection
    static RabbitMQContainer rabbitMq = new RabbitMQContainer("rabbitmq:3.13-management");

    /** Echte Datenbank, damit ON CONFLICT wirklich von PostgreSQL ausgewertet wird. */
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    @Autowired
    private MessageWriter messageWriter;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Die Tabelle überlebt die einzelne Testmethode. Ohne Leeren würde ein Test
     * die Zeilen des vorherigen mitzählen.
     */
    @BeforeEach
    void emptyTable() {
        jdbcTemplate.update("DELETE FROM message");
    }

    /** Drei Nachrichten ergeben drei Zeilen, und alle Werte kommen richtig an. */
    @Test
    void writesAllMessagesOfBatch() {
        // Mikrosekunden, weil PostgreSQL Zeitpunkte auf Mikrosekunden genau speichert.
        Instant sentAt = Instant.parse("2026-09-28T13:39:00.123456Z");
        ChatMessage first = createMessage("eins", sentAt);
        ChatMessage second = createMessage("zwei", sentAt);
        ChatMessage third = createMessage("drei", sentAt);
        List<ChatMessage> batch = List.of(first, second, third);

        int insertedRows = messageWriter.writeBatch(batch);

        int rowCount = countRows();
        assertEquals(3, insertedRows);
        assertEquals(3, rowCount);

        String sql = "SELECT sender_name, content, sent_at FROM message WHERE id = ?";
        Map<String, Object> row = jdbcTemplate.queryForMap(sql, first.id());
        Timestamp storedSentAt = (Timestamp) row.get("sent_at");
        assertEquals("Anna Muster", row.get("sender_name"));
        assertEquals("eins", row.get("content"));
        assertEquals(sentAt, storedSentAt.toInstant());
    }

    /**
     * Derselbe Stapel ein zweites Mal, wie bei einer erneuten Zustellung durch
     * RabbitMQ: Es kommt keine Zeile dazu, und es gibt keinen Fehler.
     */
    @Test
    void sameBatchAgainInsertsNothing() {
        List<ChatMessage> batch = createBatch(3);
        messageWriter.writeBatch(batch);

        int insertedSecondTime = messageWriter.writeBatch(batch);

        int rowCount = countRows();
        assertEquals(0, insertedSecondTime);
        assertEquals(3, rowCount);
    }

    /**
     * Dieselbe Nachricht zweimal im selben Stapel ergibt genau eine Zeile.
     * Das passiert, wenn beide Exemplare aus Szenario S5 im selben Stapel landen.
     */
    @Test
    void sameIdTwiceInOneBatchGivesOneRow() {
        Instant sentAt = Instant.parse("2026-09-28T13:39:00Z");
        ChatMessage message = createMessage("doppelt", sentAt);
        List<ChatMessage> batch = List.of(message, message);

        int insertedRows = messageWriter.writeBatch(batch);

        int rowCount = countRows();
        assertEquals(1, insertedRows);
        assertEquals(1, rowCount);
    }

    /** Zählt alle Zeilen der Tabelle message. */
    private int countRows() {
        Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM message", Integer.class);
        return count;
    }

    /** Baut einen Stapel aus mehreren verschiedenen Nachrichten. */
    private List<ChatMessage> createBatch(int size) {
        Instant sentAt = Instant.parse("2026-09-28T13:39:00Z");
        List<ChatMessage> batch = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            ChatMessage message = createMessage("Nachricht " + i, sentAt);
            batch.add(message);
        }
        return batch;
    }

    /** Baut eine vollständige Nachricht mit neuer id, damit die Tests kurz bleiben. */
    private ChatMessage createMessage(String content, Instant sentAt) {
        UUID id = UUID.randomUUID();
        UUID roomId = UUID.fromString("5e2a9c10-1111-4d7e-8a3b-2c4d6e8f0a1b");
        return new ChatMessage(id, roomId, "anna", "Anna Muster", content, sentAt);
    }
}
