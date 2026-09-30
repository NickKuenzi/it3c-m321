package ch.benedict.m321.batchwriter.message;

import ch.benedict.m321.batchwriter.config.QueueNames;
import ch.benedict.m321.batchwriter.dto.ChatMessage;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Hängt an der Queue chat.persist und bringt jeden Stapel in die Datenbank.
 *
 * Hier entscheidet sich die Reihenfolge, auf der die ganze Garantie beruht:
 * erst schreiben, dann bestätigen. Stürzt der Dienst dazwischen ab, stellt
 * RabbitMQ die Nachrichten erneut zu. Es geht nichts verloren, und das
 * Duplikat verwirft ON CONFLICT (Spezifikation 1, 3.1, 3.8).
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class BatchConsumer {

    private final MessageParser messageParser;

    private final MessageWriter messageWriter;

    /**
     * Wird von Spring mit einem ganzen Stapel aufgerufen: bis zu 500 Nachrichten,
     * oder weniger, wenn 200 ms lang keine neue kam (Einstellungen in RabbitConfig).
     *
     * Wir bekommen die rohen Nachrichten, nicht fertige Objekte. So lesen wir
     * das JSON selbst und hängen nicht vom Kopfeintrag __TypeId__ ab.
     *
     * Der Channel ist die Leitung zu RabbitMQ, über die wir selbst bestätigen
     * oder ablehnen.
     */
    @RabbitListener(queues = QueueNames.PERSIST_QUEUE)
    public void receiveBatch(List<Message> batch, Channel channel) throws IOException {
        List<Message> validDeliveries = new ArrayList<>();
        List<ChatMessage> validMessages = new ArrayList<>();

        for (Message delivery : batch) {
            ChatMessage chatMessage = parseOrReject(delivery, channel);
            if (chatMessage != null) {
                validDeliveries.add(delivery);
                validMessages.add(chatMessage);
            }
        }

        int insertedRows = messageWriter.writeBatch(validMessages);

        acknowledgeAll(validDeliveries, channel);

        int invalidCount = batch.size() - validMessages.size();
        int duplicateCount = validMessages.size() - insertedRows;
        log.info("Batch of {} messages: {} inserted, {} duplicates, {} invalid",
                batch.size(), insertedRows, duplicateCount, invalidCount);
    }

    /**
     * Liest eine Nachricht. Ist sie ungültig, wird sie sofort abgelehnt, ohne
     * sie zurück in die Queue zu legen. RabbitMQ leitet sie dann über die
     * Dead-Letter-Einstellung von chat.persist nach chat.dlq (Spezifikation 3.6).
     *
     * Kaputtes JSON wird beim nächsten Versuch nicht besser. Deshalb kein
     * zweiter Versuch, und die übrigen Nachrichten des Stapels laufen weiter.
     *
     * @return die gelesene Nachricht, oder null, wenn sie abgelehnt wurde
     */
    private ChatMessage parseOrReject(Message delivery, Channel channel) throws IOException {
        byte[] body = delivery.getBody();
        long deliveryTag = deliveryTagOf(delivery);

        try {
            return messageParser.parse(body);
        } catch (InvalidMessageException exception) {
            log.warn("Rejecting message with delivery tag {} to {}: {}",
                    deliveryTag, QueueNames.DEAD_LETTER_QUEUE, exception.getMessage());
            // requeue = false: nicht zurück in chat.persist, sondern nach chat.dlq.
            channel.basicReject(deliveryTag, false);
            return null;
        }
    }

    /**
     * Bestätigt jede Nachricht einzeln. Erst danach löscht RabbitMQ sie aus
     * chat.persist. Aufgerufen wird das nur, wenn sie schon in der Datenbank steht.
     */
    private void acknowledgeAll(List<Message> deliveries, Channel channel) throws IOException {
        for (Message delivery : deliveries) {
            long deliveryTag = deliveryTagOf(delivery);
            // multiple = false: nur genau diese eine Nachricht bestätigen.
            channel.basicAck(deliveryTag, false);
        }
    }

    /**
     * Die Nummer, unter der RabbitMQ diese Zustellung auf dem Channel kennt.
     * Mit ihr bestätigt oder lehnt man genau diese eine Nachricht ab.
     */
    private long deliveryTagOf(Message delivery) {
        MessageProperties properties = delivery.getMessageProperties();
        return properties.getDeliveryTag();
    }
}
