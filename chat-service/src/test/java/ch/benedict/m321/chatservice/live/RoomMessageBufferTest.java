package ch.benedict.m321.chatservice.live;

import ch.benedict.m321.chatservice.dto.ChatMessage;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prüft den Zwischenspeicher ohne RabbitMQ und ohne Spring: Reihenfolge,
 * Grenze von 100, «alles nach ID X», unbekannte ID und getrennte Räume.
 */
class RoomMessageBufferTest {

    private static final int CAPACITY = 100;

    private static final UUID ROOM_A = UUID.fromString("5e2a9c10-1111-4d7e-8a3b-2c4d6e8f0a1b");

    private static final UUID ROOM_B = UUID.fromString("7c4e1d22-2222-4f9a-9b5c-3d5e7f9a1b2c");

    private final RoomMessageBuffer buffer = new RoomMessageBuffer(CAPACITY);

    /** Nach ID 1 kommen 2 und 3, genau in dieser Reihenfolge. */
    @Test
    void returnsMessagesAfterIdInOrder() {
        ChatMessage first = createMessage(ROOM_A, "eins");
        ChatMessage second = createMessage(ROOM_A, "zwei");
        ChatMessage third = createMessage(ROOM_A, "drei");
        buffer.add(first);
        buffer.add(second);
        buffer.add(third);

        MissedMessages missed = buffer.findMessagesAfter(ROOM_A, first.id());

        assertTrue(missed.lastIdKnown());
        assertEquals(2, missed.messages().size());
        assertEquals("zwei", missed.messages().get(0).content());
        assertEquals("drei", missed.messages().get(1).content());
    }

    /** Ist die letzte ID die neueste Nachricht, fehlt nichts: bekannt, aber leer. */
    @Test
    void lastMessageKnownMeansNothingMissing() {
        ChatMessage only = createMessage(ROOM_A, "einzige");
        buffer.add(only);

        MissedMessages missed = buffer.findMessagesAfter(ROOM_A, only.id());

        assertTrue(missed.lastIdKnown());
        assertTrue(missed.messages().isEmpty());
    }

    /** Die 101. Nachricht verdrängt die älteste. Deren ID ist danach unbekannt. */
    @Test
    void oldestMessageFallsOutAfterCapacity() {
        List<ChatMessage> sent = new ArrayList<>();
        for (int i = 1; i <= CAPACITY + 1; i++) {
            ChatMessage message = createMessage(ROOM_A, "Nachricht " + i);
            buffer.add(message);
            sent.add(message);
        }
        ChatMessage oldest = sent.get(0);
        ChatMessage secondOldest = sent.get(1);

        MissedMessages afterOldest = buffer.findMessagesAfter(ROOM_A, oldest.id());
        MissedMessages afterSecond = buffer.findMessagesAfter(ROOM_A, secondOldest.id());

        assertFalse(afterOldest.lastIdKnown());
        assertTrue(afterSecond.lastIdKnown());
        assertEquals(CAPACITY - 1, afterSecond.messages().size());
    }

    /** Eine ID, die nie gesendet wurde, ist unbekannt. */
    @Test
    void unknownIdIsRecognised() {
        ChatMessage stored = createMessage(ROOM_A, "eins");
        buffer.add(stored);
        UUID neverSent = UUID.randomUUID();

        MissedMessages missed = buffer.findMessagesAfter(ROOM_A, neverSent);

        assertFalse(missed.lastIdKnown());
        assertTrue(missed.messages().isEmpty());
    }

    /** Ein Raum ohne jede Nachricht kennt auch keine ID. */
    @Test
    void emptyRoomKnowsNoId() {
        MissedMessages missed = buffer.findMessagesAfter(ROOM_B, UUID.randomUUID());

        assertFalse(missed.lastIdKnown());
    }

    /**
     * Räume sind getrennt: Nachrichten aus Raum B tauchen in Raum A nicht auf,
     * und eine ID aus Raum B ist in Raum A unbekannt.
     */
    @Test
    void roomsAreSeparated() {
        ChatMessage inA = createMessage(ROOM_A, "A1");
        ChatMessage inB = createMessage(ROOM_B, "B1");
        ChatMessage laterInA = createMessage(ROOM_A, "A2");
        buffer.add(inA);
        buffer.add(inB);
        buffer.add(laterInA);

        MissedMessages afterA = buffer.findMessagesAfter(ROOM_A, inA.id());
        MissedMessages idFromOtherRoom = buffer.findMessagesAfter(ROOM_A, inB.id());

        assertEquals(1, afterA.messages().size());
        assertEquals("A2", afterA.messages().get(0).content());
        assertFalse(idFromOtherRoom.lastIdKnown());
    }

    /** Baut eine Nachricht wie vom chat-service, mit neuer ID. */
    private ChatMessage createMessage(UUID roomId, String content) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        return new ChatMessage(id, roomId, "anna", "Anna Muster", content, now);
    }
}
