package ch.benedict.m321.chatservice.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prüft gegen einen echten RabbitMQ, dass die Live-Queue dieser Instanz
 * genau so angelegt wird, wie es die Spezifikation verlangt (3.3, 3.5, 4.2).
 *
 * Die Einstellungen einer Queue (Grenze, Überlauf, TTL) kann man über die
 * normale Verbindung nicht abfragen. Deshalb fragen wir die
 * Management-Schnittstelle des Brokers, so wie es auch rabbitmqctl tut.
 */
@SpringBootTest
@Testcontainers
class LiveQueueIntegrationTest {

    /** Echter Broker mit Management-Schnittstelle. */
    @Container
    @ServiceConnection
    static RabbitMQContainer rabbitMq = new RabbitMQContainer("rabbitmq:3.13-management");

    @Autowired
    @Qualifier("liveQueue")
    private Queue liveQueue;

    @Autowired
    private RabbitAdmin rabbitAdmin;

    @Autowired
    private ObjectMapper objectMapper;

    /** Die Queue existiert im Broker und ihr Name beginnt mit chat.live. */
    @Test
    void liveQueueExistsInBroker() {
        String queueName = liveQueue.getName();

        QueueInformation queueInformation = rabbitAdmin.getQueueInfo(queueName);

        assertNotNull(queueInformation, "live queue not found in broker");
        assertTrue(queueName.startsWith(QueueNames.LIVE_QUEUE_PREFIX), "name was " + queueName);
    }

    /**
     * Grenze 990 wartend, älteste fliegt raus, 30 s TTL, exklusiv, Auto-Delete,
     * nicht dauerhaft. Dazu prefetch 10 beim Verbraucher: zusammen höchstens
     * 1000 Nachrichten in der Queue (S6). So wie im Broker eingetragen, nicht
     * nur im Code.
     */
    @Test
    void liveQueueHasSettingsFromSpecification() throws IOException, InterruptedException {
        String encodedName = URLEncoder.encode(liveQueue.getName(), StandardCharsets.UTF_8);
        JsonNode queue = readManagementApi("/api/queues/%2F/" + encodedName);

        JsonNode arguments = queue.get("arguments");
        assertEquals(990, arguments.get("x-max-length").asInt());
        assertEquals("drop-head", arguments.get("x-overflow").asText());
        assertEquals(30000, arguments.get("x-message-ttl").asInt());
        assertTrue(queue.get("exclusive").asBoolean(), "queue must be exclusive");
        assertTrue(queue.get("auto_delete").asBoolean(), "queue must be auto-delete");
        assertEquals(false, queue.get("durable").asBoolean());
    }

    /**
     * Der Verbraucher holt höchstens 10 Nachrichten im Voraus. Zusammen mit
     * den 990 wartenden hält die Queue so nie mehr als 1000 (S6).
     *
     * Die Management-Schnittstelle aktualisiert ihre Angaben zu Verbrauchern
     * nur alle paar Sekunden. Darum wird kurz gewartet, bis er auftaucht.
     */
    @Test
    void consumerFetchesAtMostTenInAdvance() throws IOException, InterruptedException {
        String encodedName = URLEncoder.encode(liveQueue.getName(), StandardCharsets.UTF_8);
        JsonNode consumers = waitForConsumerDetails("/api/queues/%2F/" + encodedName);

        assertEquals(1, consumers.size(), "exactly one consumer expected");
        JsonNode consumer = consumers.get(0);
        assertEquals(10, consumer.get("prefetch_count").asInt());
    }

    /** Fragt die Queue ab, bis die Angaben zum Verbraucher da sind, höchstens 15 s. */
    private JsonNode waitForConsumerDetails(String path) throws IOException, InterruptedException {
        JsonNode consumers = null;
        for (int i = 0; i < 30; i++) {
            JsonNode queue = readManagementApi(path);
            consumers = queue.get("consumer_details");
            if (consumers != null && consumers.size() > 0) {
                return consumers;
            }
            Thread.sleep(500);
        }
        return consumers;
    }

    /** Die Queue hängt am Fanout-Exchange chat.delivery, sonst kommt nichts an. */
    @Test
    void liveQueueIsBoundToDeliveryExchange() throws IOException, InterruptedException {
        JsonNode bindings = readManagementApi("/api/exchanges/%2F/chat.delivery/bindings/source");

        boolean found = false;
        for (JsonNode binding : bindings) {
            String destination = binding.get("destination").asText();
            if (destination.equals(liveQueue.getName())) {
                found = true;
            }
        }
        assertTrue(found, "live queue is not bound to " + QueueNames.DELIVERY_EXCHANGE);
    }

    /**
     * Ruft eine Adresse der Management-Schnittstelle auf und liefert die
     * Antwort als JSON. Benutzer und Passwort kommen vom Test-Container.
     */
    private JsonNode readManagementApi(String path) throws IOException, InterruptedException {
        String credentials = rabbitMq.getAdminUsername() + ":" + rabbitMq.getAdminPassword();
        byte[] credentialBytes = credentials.getBytes(StandardCharsets.UTF_8);
        Base64.Encoder encoder = Base64.getEncoder();
        String encodedCredentials = encoder.encodeToString(credentialBytes);
        URI address = URI.create(rabbitMq.getHttpUrl() + path);

        HttpRequest request = HttpRequest.newBuilder(address)
                .header("Authorization", "Basic " + encodedCredentials)
                .GET()
                .build();
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode(), "management API answered " + response.body());
        return objectMapper.readTree(response.body());
    }
}
