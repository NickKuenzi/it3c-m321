package ch.benedict.m321.batchwriter.message;

import ch.benedict.m321.batchwriter.dto.ChatMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Macht aus dem Inhalt einer Queue-Nachricht ein ChatMessage.
 *
 * Er bekommt nur den Body und nie die Kopfeinträge. Deshalb spielt es keine
 * Rolle, ob der chat-service __TypeId__ mitschickt oder ob die Nachricht wie in
 * Szenario S5 nur mit content_type ankommt (Spezifikation 2.3).
 */
@Component
@RequiredArgsConstructor
public class MessageParser {

    /**
     * Der ObjectMapper von Spring Boot. Er kennt Instant im ISO-Format und
     * ignoriert unbekannte Felder, genau wie der des chat-service.
     */
    private final ObjectMapper objectMapper;

    /**
     * Liest den Body als JSON und prüft, dass alle sechs Felder vorhanden sind.
     *
     * @throws InvalidMessageException wenn der Body kein gültiges JSON ist oder ein Feld fehlt
     */
    public ChatMessage parse(byte[] body) {
        ChatMessage message = readJson(body);

        // Der Body "null" ist gültiges JSON, ergibt aber keine Nachricht.
        if (message == null) {
            throw new InvalidMessageException("body is the JSON literal null");
        }

        checkRequiredFields(message);
        return message;
    }

    /**
     * Übersetzt jeden Lesefehler von Jackson in eine InvalidMessageException.
     * Dazu gehören kaputtes JSON, ein leerer Body, eine id, die keine UUID ist,
     * und ein sentAt, das kein Zeitpunkt ist.
     */
    private ChatMessage readJson(byte[] body) {
        try {
            return objectMapper.readValue(body, ChatMessage.class);
        } catch (IOException exception) {
            String reason = "body is not a valid chat message: " + exception.getMessage();
            throw new InvalidMessageException(reason, exception);
        }
    }

    /**
     * Fehlt ein Feld im JSON, setzt Jackson es auf null. Das fangen wir hier ab,
     * sonst würde die Datenbank die Zeile erst später wegen NOT NULL ablehnen.
     */
    private void checkRequiredFields(ChatMessage message) {
        requirePresent(message.id(), "id");
        requirePresent(message.roomId(), "roomId");
        requirePresent(message.senderId(), "senderId");
        requirePresent(message.senderName(), "senderName");
        requirePresent(message.content(), "content");
        requirePresent(message.sentAt(), "sentAt");
    }

    /** Wirft eine InvalidMessageException, wenn der Wert fehlt. */
    private void requirePresent(Object value, String fieldName) {
        if (value == null) {
            throw new InvalidMessageException("field " + fieldName + " is missing");
        }
    }
}
