package ch.benedict.m321.chatservice.live;

import ch.benedict.m321.chatservice.config.QueueNames;
import ch.benedict.m321.chatservice.dto.ChatMessage;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prüft den ganzen Empfangsweg mit echtem RabbitMQ: Nachricht an den
 * Fanout-Exchange chat.delivery, durch die Live-Queue dieser Instanz, in den
 * Zwischenspeicher ihres Raums.
 */
@SpringBootTest
@Testcontainers
class LiveMessageListenerIntegrationTest {

    /** Echter Broker, damit Exchange, Bindung und Queue wirklich arbeiten. */
    @Container
    @ServiceConnection
    static RabbitMQContainer rabbitMq = new RabbitMQContainer("rabbitmq:3.13-management");

    /** Wie lange höchstens auf den Verbraucher gewartet wird, in Zehntelsekunden. */
    private static final int MAX_WAIT_TENTHS = 100;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private RoomMessageBuffer roomMessageBuffer;

    /**
     * Zwei Nachrichten in denselben Raum. Steht die zweite im Speicher «nach»
     * der ersten, sind beide angekommen, und zwar in der richtigen Reihenfolge.
     */
    @Test
    void messageFromExchangeEndsUpInBufferOfItsRoom() throws InterruptedException {
        UUID roomId = UUID.randomUUID();
        ChatMessage first = createMessage(roomId, "erste");
        ChatMessage second = createMessage(roomId, "zweite");

        sendToDeliveryExchange(first);
        sendToDeliveryExchange(second);

        MissedMessages afterFirst = waitForMessagesAfter(roomId, first.id(), 1);
        assertTrue(afterFirst.lastIdKnown(), "first message not in buffer");
        assertEquals(1, afterFirst.messages().size());
        assertEquals(second.id(), afterFirst.messages().get(0).id());
    }

    /**
     * Sendet so wie der chat-service selbst (MessagePublisher): an den
     * Fanout-Exchange, Routing-Key leer, Umwandlung in JSON.
     */
    private void sendToDeliveryExchange(ChatMessage message) {
        rabbitTemplate.convertAndSend(QueueNames.DELIVERY_EXCHANGE, "", message);
    }

    /**
     * Fragt den Zwischenspeicher, bis nach lastId die erwartete Anzahl
     * Nachrichten steht. Der Verbraucher arbeitet in einem eigenen Thread,
     * das Ergebnis kommt also etwas später.
     */
    private MissedMessages waitForMessagesAfter(UUID roomId, UUID lastId, int expectedCount)
            throws InterruptedException {
        for (int i = 0; i < MAX_WAIT_TENTHS; i++) {
            MissedMessages missed = roomMessageBuffer.findMessagesAfter(roomId, lastId);
            if (missed.messages().size() == expectedCount) {
                return missed;
            }
            Thread.sleep(100);
        }
        return roomMessageBuffer.findMessagesAfter(roomId, lastId);
    }

    /** Baut eine Nachricht wie vom chat-service, mit neuer ID. */
    private ChatMessage createMessage(UUID roomId, String content) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        return new ChatMessage(id, roomId, "anna", "Anna Muster", content, now);
    }
}
