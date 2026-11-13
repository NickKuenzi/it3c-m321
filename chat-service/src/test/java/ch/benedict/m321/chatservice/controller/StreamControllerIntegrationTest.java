package ch.benedict.m321.chatservice.controller;

import ch.benedict.m321.chatservice.config.QueueNames;
import ch.benedict.m321.chatservice.dto.ChatMessage;
import ch.benedict.m321.chatservice.live.SseTestClient;
import ch.benedict.m321.chatservice.live.SseTestClient.SseTestEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Der Anzeigeweg mit echtem RabbitMQ und echtem HTTP: Nachricht an
 * chat.delivery, durch die Live-Queue, über GET /stream zum Client
 * (Spezifikation 2.2, 3.1, 3.2).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class StreamControllerIntegrationTest {

    /** Echter Broker, damit der ganze Weg wirklich durchlaufen wird. */
    @Container
    @ServiceConnection
    static RabbitMQContainer rabbitMq = new RabbitMQContainer("rabbitmq:3.13-management");

    @LocalServerPort
    private int port;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * Ein Client in Raum A, einer in Raum B. Erst eine Nachricht für A, dann
     * eine für B. Jeder bekommt genau seine eine, mit id: und data:.
     *
     * Dass B die Nachricht für A NICHT bekommt, sieht man so: Die Nachricht
     * für A wurde zuerst gesendet. Hat B am Ende genau ein Ereignis, und zwar
     * das eigene, ist die von A an B vorbeigegangen.
     */
    @Test
    void eachClientReceivesOnlyMessagesOfItsRoom() throws Exception {
        UUID roomA = UUID.randomUUID();
        UUID roomB = UUID.randomUUID();
        SseTestClient clientA = connect(roomA);
        SseTestClient clientB = connect(roomB);
        assertEquals(200, clientA.statusCode());
        String contentType = clientA.contentType();
        assertTrue(contentType.startsWith("text/event-stream"), contentType);

        ChatMessage forA = createMessage(roomA, "fuer Raum A");
        ChatMessage forB = createMessage(roomB, "fuer Raum B");
        sendToDeliveryExchange(forA);
        sendToDeliveryExchange(forB);

        List<SseTestEvent> eventsA = clientA.waitForEvents(1);
        List<SseTestEvent> eventsB = clientB.waitForEvents(1);
        clientA.close();
        clientB.close();

        assertEquals(1, eventsA.size());
        assertEquals(1, eventsB.size());
        SseTestEvent eventA = eventsA.get(0);
        SseTestEvent eventB = eventsB.get(0);
        assertEquals(forA.id().toString(), eventA.id());
        assertEquals(forB.id().toString(), eventB.id());

        // data: enthält dieselbe Nachricht als JSON, Feld für Feld gleich.
        ChatMessage receivedByA = objectMapper.readValue(eventA.data(), ChatMessage.class);
        assertEquals(forA, receivedByA);
    }

    /** Fehlt die roomId, gibt es 400 (Spezifikation 2.2). */
    @Test
    void missingRoomIdGivesBadRequest() throws IOException, InterruptedException {
        int status = statusOf("/stream");

        assertEquals(400, status);
    }

    /** Ist die roomId keine UUID, gibt es 400 (Spezifikation 2.2). */
    @Test
    void invalidRoomIdGivesBadRequest() throws IOException, InterruptedException {
        int status = statusOf("/stream?roomId=keine-uuid");

        assertEquals(400, status);
    }

    /** Öffnet einen Stream für einen Raum, ohne Last-Event-ID. */
    private SseTestClient connect(UUID roomId) throws IOException, InterruptedException {
        String url = "http://localhost:" + port + "/stream?roomId=" + roomId;
        return new SseTestClient(url, null);
    }

    /** Sendet so wie MessagePublisher: an den Fanout-Exchange, als JSON. */
    private void sendToDeliveryExchange(ChatMessage message) {
        rabbitTemplate.convertAndSend(QueueNames.DELIVERY_EXCHANGE, "", message);
    }

    /** Ruft eine Adresse auf und liefert nur den HTTP-Status. */
    private int statusOf(String path) throws IOException, InterruptedException {
        URI address = URI.create("http://localhost:" + port + path);
        HttpRequest request = HttpRequest.newBuilder(address).GET().build();
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        return response.statusCode();
    }

    /** Baut eine Nachricht wie vom chat-service, mit neuer ID. */
    private ChatMessage createMessage(UUID roomId, String content) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        return new ChatMessage(id, roomId, "anna", "Anna Muster", content, now);
    }
}
