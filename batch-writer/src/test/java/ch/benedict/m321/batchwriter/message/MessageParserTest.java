package ch.benedict.m321.batchwriter.message;

import ch.benedict.m321.batchwriter.dto.ChatMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Prüft den Vertrag aus Spezifikation 2: Was der chat-service in die Queue
 * legt, muss sich lesen lassen, und alles andere muss als ungültig auffallen.
 *
 * Ohne Spring und ohne Container, deshalb schnell. Der ObjectMapper kommt aus
 * demselben Baukasten, den auch Spring Boot benutzt, und ist damit gleich
 * eingestellt wie in der laufenden Anwendung.
 */
class MessageParserTest {

    private final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();

    private final MessageParser messageParser = new MessageParser(objectMapper);

    /** Eine Nachricht genau im Format des chat-service wird vollständig gelesen. */
    @Test
    void readsMessageInChatServiceFormat() {
        String json = """
                {"id":"0b8f6a52-3c1e-4c55-9d0a-7f3e2c1b9a44",
                 "roomId":"5e2a9c10-1111-4d7e-8a3b-2c4d6e8f0a1b",
                 "senderId":"anna",
                 "senderName":"Anna Muster",
                 "content":"Hallo zusammen",
                 "sentAt":"2026-09-28T13:39:00.123456789Z"}
                """;

        ChatMessage message = messageParser.parse(toBytes(json));

        UUID expectedId = UUID.fromString("0b8f6a52-3c1e-4c55-9d0a-7f3e2c1b9a44");
        UUID expectedRoomId = UUID.fromString("5e2a9c10-1111-4d7e-8a3b-2c4d6e8f0a1b");
        Instant expectedSentAt = Instant.parse("2026-09-28T13:39:00.123456789Z");
        assertEquals(expectedId, message.id());
        assertEquals(expectedRoomId, message.roomId());
        assertEquals("anna", message.senderId());
        assertEquals("Anna Muster", message.senderName());
        assertEquals("Hallo zusammen", message.content());
        assertEquals(expectedSentAt, message.sentAt());
    }

    /**
     * Ein zusätzliches, unbekanntes Feld stört nicht. So kann der chat-service
     * später Felder ergänzen, ohne den batch-writer zu brechen.
     */
    @Test
    void ignoresUnknownFields() {
        String json = """
                {"id":"0b8f6a52-3c1e-4c55-9d0a-7f3e2c1b9a44",
                 "roomId":"5e2a9c10-1111-4d7e-8a3b-2c4d6e8f0a1b",
                 "senderId":"anna",
                 "senderName":"Anna Muster",
                 "content":"Hallo",
                 "sentAt":"2026-09-28T13:39:00Z",
                 "priority":"high"}
                """;

        ChatMessage message = messageParser.parse(toBytes(json));

        assertEquals("Hallo", message.content());
    }

    /** Text, der kein JSON ist, ist ungültig. */
    @Test
    void rejectsBodyThatIsNotJson() {
        byte[] body = toBytes("Hallo, ich bin kein JSON");

        assertThrows(InvalidMessageException.class, () -> messageParser.parse(body));
    }

    /** Ein leerer Body ist ungültig. */
    @Test
    void rejectsEmptyBody() {
        byte[] body = new byte[0];

        assertThrows(InvalidMessageException.class, () -> messageParser.parse(body));
    }

    /** Das JSON-Literal null ist gültiges JSON, aber keine Nachricht. */
    @Test
    void rejectsJsonNull() {
        byte[] body = toBytes("null");

        assertThrows(InvalidMessageException.class, () -> messageParser.parse(body));
    }

    /** Ohne id gibt es keinen Schutz gegen Duplikate, also ist die Nachricht ungültig. */
    @Test
    void rejectsMissingId() {
        String json = """
                {"roomId":"5e2a9c10-1111-4d7e-8a3b-2c4d6e8f0a1b",
                 "senderId":"anna",
                 "senderName":"Anna Muster",
                 "content":"Hallo",
                 "sentAt":"2026-09-28T13:39:00Z"}
                """;
        byte[] body = toBytes(json);

        assertThrows(InvalidMessageException.class, () -> messageParser.parse(body));
    }

    /** Eine id, die keine UUID ist, ist ungültig. */
    @Test
    void rejectsIdThatIsNotUuid() {
        String json = """
                {"id":"nachricht-1",
                 "roomId":"5e2a9c10-1111-4d7e-8a3b-2c4d6e8f0a1b",
                 "senderId":"anna",
                 "senderName":"Anna Muster",
                 "content":"Hallo",
                 "sentAt":"2026-09-28T13:39:00Z"}
                """;
        byte[] body = toBytes(json);

        assertThrows(InvalidMessageException.class, () -> messageParser.parse(body));
    }

    /** Ein sentAt, das kein Zeitpunkt ist, ist ungültig. */
    @Test
    void rejectsSentAtThatIsNotTimestamp() {
        String json = """
                {"id":"0b8f6a52-3c1e-4c55-9d0a-7f3e2c1b9a44",
                 "roomId":"5e2a9c10-1111-4d7e-8a3b-2c4d6e8f0a1b",
                 "senderId":"anna",
                 "senderName":"Anna Muster",
                 "content":"Hallo",
                 "sentAt":"gestern"}
                """;
        byte[] body = toBytes(json);

        assertThrows(InvalidMessageException.class, () -> messageParser.parse(body));
    }

    /** Wandelt Text in die Bytes um, die auch in der Queue liegen würden. */
    private byte[] toBytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
