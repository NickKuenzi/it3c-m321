package ch.benedict.m321.chatservice.live;

import ch.benedict.m321.chatservice.config.QueueNames;
import ch.benedict.m321.chatservice.dto.ChatMessage;
import ch.benedict.m321.chatservice.live.SseTestClient.SseTestEvent;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Szenario S7 im Kleinen: Ein Client trennt, verpasst Nachrichten und
 * verbindet sich mit Last-Event-ID neu (Spezifikation 2.3, 3.6).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ReconnectIntegrationTest {

    /** Echter Broker, damit die Nachrichten wirklich durch die Live-Queue gehen. */
    @Container
    @ServiceConnection
    static RabbitMQContainer rabbitMq = new RabbitMQContainer("rabbitmq:3.13-management");

    /** So viele Nachrichten verpasst der Client, wie im Szenario S7. */
    private static final int MISSED_COUNT = 50;

    /** Wie lange höchstens auf den Zwischenspeicher gewartet wird, in Zehntelsekunden. */
    private static final int MAX_WAIT_TENTHS = 150;

    @LocalServerPort
    private int port;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private RoomMessageBuffer roomMessageBuffer;

    /**
     * Client empfängt eine Nachricht und trennt. 50 Nachrichten kommen, während
     * er weg ist. Er verbindet neu mit der ID der ersten und erhält genau die
     * 50, in Reihenfolge, keine doppelt. Danach kommt eine weitere live.
     */
    @Test
    void reconnectWithLastEventIdDeliversExactlyTheMissedMessages() throws Exception {
        UUID roomId = UUID.randomUUID();

        SseTestClient firstConnection = connect(roomId, null);
        ChatMessage seen = createMessage(roomId, "gesehen");
        sendToDeliveryExchange(seen);
        List<SseTestEvent> firstEvents = firstConnection.waitForEvents(1);
        String lastEventId = firstEvents.get(0).id();
        firstConnection.close();

        List<ChatMessage> missed = new ArrayList<>();
        for (int i = 1; i <= MISSED_COUNT; i++) {
            ChatMessage message = createMessage(roomId, "verpasst " + i);
            sendToDeliveryExchange(message);
            missed.add(message);
        }
        // Warten, bis alle 50 im Zwischenspeicher sind. Sonst kämen sie
        // vielleicht live statt nachgeholt, und der Test bewiese nichts.
        waitUntilBufferHasAfter(roomId, seen.id(), MISSED_COUNT);

        SseTestClient secondConnection = connect(roomId, lastEventId);
        List<SseTestEvent> replayed = secondConnection.waitForEvents(MISSED_COUNT);

        ChatMessage afterReconnect = createMessage(roomId, "wieder live");
        sendToDeliveryExchange(afterReconnect);
        List<SseTestEvent> allEvents = secondConnection.waitForEvents(MISSED_COUNT + 1);
        secondConnection.close();

        assertEquals(MISSED_COUNT, replayed.size());
        assertEquals(MISSED_COUNT + 1, allEvents.size());
        for (int i = 0; i < MISSED_COUNT; i++) {
            String expectedId = missed.get(i).id().toString();
            SseTestEvent event = allEvents.get(i);
            assertEquals(expectedId, event.id(), "wrong message at position " + i);
        }
        SseTestEvent lastEvent = allEvents.get(MISSED_COUNT);
        assertEquals(afterReconnect.id().toString(), lastEvent.id());
        assertEquals(MISSED_COUNT + 1, countDistinctIds(allEvents), "a message came twice");
    }

    /**
     * Unbekannte Last-Event-ID: zuerst ein resync-Ereignis ohne id:, danach
     * läuft die Zustellung live weiter (Spezifikation 2.3).
     */
    @Test
    void unknownLastEventIdGivesResyncThenLive() throws Exception {
        UUID roomId = UUID.randomUUID();
        UUID neverSent = UUID.randomUUID();
        String unknownId = neverSent.toString();

        SseTestClient client = connect(roomId, unknownId);
        ChatMessage live = createMessage(roomId, "live nach resync");
        sendToDeliveryExchange(live);
        List<SseTestEvent> events = client.waitForEvents(2);
        client.close();

        assertEquals(2, events.size());
        SseTestEvent resync = events.get(0);
        assertEquals("resync", resync.name());
        assertNull(resync.id(), "resync must not carry an id");
        SseTestEvent liveEvent = events.get(1);
        assertEquals(live.id().toString(), liveEvent.id());
    }

    /**
     * Ohne Last-Event-ID wird nichts nachgeliefert, auch wenn der Raum schon
     * Nachrichten hat. Der Client bekommt nur, was ab jetzt kommt.
     */
    @Test
    void newConnectionWithoutLastEventIdGetsOnlyNewMessages() throws Exception {
        UUID roomId = UUID.randomUUID();
        ChatMessage old = createMessage(roomId, "alt");
        sendToDeliveryExchange(old);
        ChatMessage marker = createMessage(roomId, "markierung");
        sendToDeliveryExchange(marker);
        waitUntilBufferHasAfter(roomId, old.id(), 1);

        SseTestClient client = connect(roomId, null);
        ChatMessage fresh = createMessage(roomId, "neu");
        sendToDeliveryExchange(fresh);
        client.waitForEvents(1);
        // Kurz weiter zuhören: Käme doch noch etwas Altes, wären es mehr als 1.
        Thread.sleep(500);
        List<SseTestEvent> events = client.events();
        client.close();

        assertEquals(1, events.size());
        SseTestEvent onlyEvent = events.get(0);
        assertEquals(fresh.id().toString(), onlyEvent.id());
    }

    /** Öffnet einen Stream, mit oder ohne Last-Event-ID. */
    private SseTestClient connect(UUID roomId, String lastEventId) throws IOException, InterruptedException {
        String url = "http://localhost:" + port + "/stream?roomId=" + roomId;
        return new SseTestClient(url, lastEventId);
    }

    /** Sendet so wie MessagePublisher: an den Fanout-Exchange, als JSON. */
    private void sendToDeliveryExchange(ChatMessage message) {
        rabbitTemplate.convertAndSend(QueueNames.DELIVERY_EXCHANGE, "", message);
    }

    /** Wartet, bis im Zwischenspeicher nach lastId so viele Nachrichten stehen. */
    private void waitUntilBufferHasAfter(UUID roomId, UUID lastId, int expectedCount)
            throws InterruptedException {
        for (int i = 0; i < MAX_WAIT_TENTHS; i++) {
            MissedMessages stored = roomMessageBuffer.findMessagesAfter(roomId, lastId);
            if (stored.messages().size() >= expectedCount) {
                return;
            }
            Thread.sleep(100);
        }
    }

    /** Zählt verschiedene IDs. Gleich viele wie Ereignisse heisst: keine doppelt. */
    private int countDistinctIds(List<SseTestEvent> events) {
        Set<String> ids = new HashSet<>();
        for (SseTestEvent event : events) {
            ids.add(event.id());
        }
        return ids.size();
    }

    /** Baut eine Nachricht wie vom chat-service, mit neuer ID. */
    private ChatMessage createMessage(UUID roomId, String content) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        return new ChatMessage(id, roomId, "anna", "Anna Muster", content, now);
    }
}
