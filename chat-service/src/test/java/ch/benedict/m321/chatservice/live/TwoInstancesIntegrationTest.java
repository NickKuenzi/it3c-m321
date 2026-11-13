package ch.benedict.m321.chatservice.live;

import ch.benedict.m321.chatservice.ChatServiceApplication;
import ch.benedict.m321.chatservice.dto.ChatMessage;
import ch.benedict.m321.chatservice.live.SseTestClient.SseTestEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.boot.web.server.WebServer;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Szenario S3 im Kleinen: zwei chat-service-Instanzen an EINEM RabbitMQ
 * (Spezifikation 3.2).
 *
 * Hier reicht @SpringBootTest nicht, denn das startet nur eine Anwendung.
 * Deshalb starten wir zwei vollständige Anwendungen selbst, jede mit eigenem
 * Webserver auf einem freien Port, beide am selben Test-Broker. Genau wie
 * bei --scale chat-service=2, nur im selben Prozess.
 */
@Testcontainers
class TwoInstancesIntegrationTest {

    /** Ein Broker für beide Instanzen. */
    @Container
    static RabbitMQContainer rabbitMq = new RabbitMQContainer("rabbitmq:3.13-management");

    /** So viele Nachrichten pro Instanz, wie im Szenario S3. */
    private static final int MESSAGES_PER_INSTANCE = 200;

    private static ConfigurableApplicationContext instanceOne;

    private static ConfigurableApplicationContext instanceTwo;

    /** Startet die zwei Instanzen, nachdem der Broker läuft. */
    @BeforeAll
    static void startTwoInstances() {
        instanceOne = startInstance();
        instanceTwo = startInstance();
    }

    /**
     * Fährt beide Instanzen wieder herunter. Ihre Verbindungen zu RabbitMQ
     * gehen zu, und ihre exklusiven Live-Queues verschwinden (wie bei S4).
     */
    @AfterAll
    static void stopTwoInstances() {
        if (instanceOne != null) {
            instanceOne.close();
        }
        if (instanceTwo != null) {
            instanceTwo.close();
        }
    }

    /**
     * Jede Instanz hat ihre eigene Live-Queue, und an jeder hängt genau ein
     * Verbraucher. Gleiche Namen würden sich gegenseitig blockieren, weil
     * die Queues exklusiv sind.
     */
    @Test
    void eachInstanceHasItsOwnLiveQueueWithOneConsumer() {
        Queue queueOne = instanceOne.getBean("liveQueue", Queue.class);
        Queue queueTwo = instanceTwo.getBean("liveQueue", Queue.class);
        RabbitAdmin adminOne = instanceOne.getBean(RabbitAdmin.class);
        RabbitAdmin adminTwo = instanceTwo.getBean(RabbitAdmin.class);

        QueueInformation infoOne = adminOne.getQueueInfo(queueOne.getName());
        QueueInformation infoTwo = adminTwo.getQueueInfo(queueTwo.getName());

        assertNotEquals(queueOne.getName(), queueTwo.getName());
        assertEquals(1, infoOne.getConsumerCount());
        assertEquals(1, infoTwo.getConsumerCount());
    }

    /**
     * S3: Ein Client in Raum A an Instanz 1, einer in Raum A an Instanz 2,
     * einer in Raum B an Instanz 1. Je 200 Nachrichten für Raum A über jede
     * Instanz, abwechselnd gesendet.
     *
     * Erwartet: Beide A-Clients erhalten alle 400, jede genau einmal, und die
     * Nachrichten eines Absenders in seiner Reihenfolge. Der B-Client erhält
     * keine davon: Am Ende schicken wir ihm eine eigene Nachricht, und genau
     * diese eine muss er haben.
     */
    @Test
    void bothInstancesDeliverEveryMessageToTheirClients() throws Exception {
        UUID roomA = UUID.randomUUID();
        UUID roomB = UUID.randomUUID();
        int portOne = portOf(instanceOne);
        int portTwo = portOf(instanceTwo);
        SseTestClient clientAtOne = connect(portOne, roomA);
        SseTestClient clientAtTwo = connect(portTwo, roomA);
        SseTestClient clientInRoomB = connect(portOne, roomB);

        for (int i = 1; i <= MESSAGES_PER_INSTANCE; i++) {
            postMessage(portOne, roomA, "sender-1", "sender-1 " + i);
            postMessage(portTwo, roomA, "sender-2", "sender-2 " + i);
        }
        postMessage(portTwo, roomB, "sender-3", "nur fuer Raum B");

        int expectedTotal = 2 * MESSAGES_PER_INSTANCE;
        clientAtOne.waitForEvents(expectedTotal);
        clientAtTwo.waitForEvents(expectedTotal);
        clientInRoomB.waitForEvents(1);
        // Kurz weiter zuhören: Käme noch etwas Falsches, wären es zu viele.
        Thread.sleep(500);
        List<SseTestEvent> finalAtOne = clientAtOne.events();
        List<SseTestEvent> finalAtTwo = clientAtTwo.events();
        List<SseTestEvent> finalInRoomB = clientInRoomB.events();
        clientAtOne.close();
        clientAtTwo.close();
        clientInRoomB.close();

        assertEquals(expectedTotal, finalAtOne.size(), "client at instance 1");
        assertEquals(expectedTotal, finalAtTwo.size(), "client at instance 2");
        assertEquals(expectedTotal, countDistinctIds(finalAtOne), "duplicate at instance 1");
        assertEquals(expectedTotal, countDistinctIds(finalAtTwo), "duplicate at instance 2");
        assertOrderPerSender(finalAtOne);
        assertOrderPerSender(finalAtTwo);

        assertEquals(1, finalInRoomB.size(), "client in room B got messages of room A");
        SseTestEvent eventInRoomB = finalInRoomB.get(0);
        ChatMessage onlyInRoomB = readMessage(eventInRoomB);
        assertEquals("nur fuer Raum B", onlyInRoomB.content());
    }

    /**
     * Startet eine vollständige chat-service-Anwendung: freier Port, Broker
     * aus dem Test-Container. Die Werte gehen als Eigenschaften hinein, so
     * wie sonst über die Umgebungsvariablen.
     */
    private static ConfigurableApplicationContext startInstance() {
        String host = "--spring.rabbitmq.host=" + rabbitMq.getHost();
        String port = "--spring.rabbitmq.port=" + rabbitMq.getAmqpPort();
        String user = "--spring.rabbitmq.username=" + rabbitMq.getAdminUsername();
        String password = "--spring.rabbitmq.password=" + rabbitMq.getAdminPassword();
        String freePort = "--server.port=0";

        SpringApplicationBuilder builder = new SpringApplicationBuilder(ChatServiceApplication.class);
        return builder.run(host, port, user, password, freePort);
    }

    /** Der Port, auf dem der Webserver dieser Instanz gestartet ist. */
    private int portOf(ConfigurableApplicationContext instance) {
        WebServerApplicationContext webContext = (WebServerApplicationContext) instance;
        WebServer webServer = webContext.getWebServer();
        return webServer.getPort();
    }

    /** Öffnet einen Stream an einer bestimmten Instanz, ohne Last-Event-ID. */
    private SseTestClient connect(int port, UUID roomId) throws IOException, InterruptedException {
        String url = "http://localhost:" + port + "/stream?roomId=" + roomId;
        return new SseTestClient(url, null);
    }

    /**
     * Schickt eine Nachricht über POST /messages an eine bestimmte Instanz,
     * also über den echten Weg mit MessagePublisher. Erwartet 202.
     */
    private void postMessage(int port, UUID roomId, String senderId, String content)
            throws IOException, InterruptedException {
        String body = """
                {"roomId":"%s","senderId":"%s","senderName":"%s","content":"%s"}"""
                .formatted(roomId, senderId, senderId, content);
        URI address = URI.create("http://localhost:" + port + "/messages");
        HttpRequest.BodyPublisher bodyPublisher = HttpRequest.BodyPublishers.ofString(body);
        HttpRequest request = HttpRequest.newBuilder(address)
                .header("Content-Type", "application/json")
                .POST(bodyPublisher)
                .build();
        HttpClient client = HttpClient.newHttpClient();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(202, response.statusCode(), response.body());
    }

    /**
     * Prüft die Reihenfolge pro Absender: Die Nachrichten von sender-1 müssen
     * als «sender-1 1», «sender-1 2», … ankommen, ebenso die von sender-2.
     * Wie die beiden Absender untereinander gemischt sind, ist egal.
     */
    private void assertOrderPerSender(List<SseTestEvent> events) throws IOException {
        List<String> contentsOfSenderOne = new ArrayList<>();
        List<String> contentsOfSenderTwo = new ArrayList<>();
        for (SseTestEvent event : events) {
            ChatMessage message = readMessage(event);
            if (message.senderId().equals("sender-1")) {
                contentsOfSenderOne.add(message.content());
            } else {
                contentsOfSenderTwo.add(message.content());
            }
        }
        for (int i = 0; i < MESSAGES_PER_INSTANCE; i++) {
            int number = i + 1;
            assertEquals("sender-1 " + number, contentsOfSenderOne.get(i));
            assertEquals("sender-2 " + number, contentsOfSenderTwo.get(i));
        }
    }

    /** Liest das data:-JSON eines Ereignisses als Nachricht. */
    private ChatMessage readMessage(SseTestEvent event) throws IOException {
        ObjectMapper objectMapper = instanceOne.getBean(ObjectMapper.class);
        return objectMapper.readValue(event.data(), ChatMessage.class);
    }

    /** Zählt verschiedene IDs. Gleich viele wie Ereignisse heisst: keine doppelt. */
    private int countDistinctIds(List<SseTestEvent> events) {
        Set<String> ids = new HashSet<>();
        for (SseTestEvent event : events) {
            ids.add(event.id());
        }
        return ids.size();
    }
}
