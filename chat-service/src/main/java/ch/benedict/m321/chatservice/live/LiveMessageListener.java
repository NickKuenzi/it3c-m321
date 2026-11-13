package ch.benedict.m321.chatservice.live;

import ch.benedict.m321.chatservice.dto.ChatMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Holt die Nachrichten aus der Live-Queue dieser Instanz ab
 * (Spezifikation 3.1, Schritte 5 und 6).
 *
 * Jede Nachricht geht an LiveDelivery. Dort kommt sie zuerst in den
 * Zwischenspeicher ihres Raums, auch wenn gerade kein Client im Raum ist,
 * und dann an die offenen Verbindungen des Raums (Spezifikation 3.1, 4.1).
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class LiveMessageListener {

    private final LiveDelivery liveDelivery;

    /**
     * Wird für jede Nachricht aus der Live-Queue aufgerufen.
     *
     * "#{liveQueue.name}" holt den Namen aus der Bean liveQueue in
     * RabbitConfig. Fest hinschreiben geht nicht, weil der Name bei jedem
     * Start einen zufälligen Teil bekommt.
     *
     * concurrency = "1": genau ein Verbraucher pro Instanz. Mit mehreren
     * würden Nachrichten parallel verarbeitet und könnten sich überholen;
     * die Reihenfolge pro Absender wäre weg (Spezifikation 3.2).
     *
     * Den Typ ChatMessage wandelt der JSON-Umwandler aus RabbitConfig um,
     * derselbe, mit dem die Nachricht gesendet wurde.
     */
    @RabbitListener(queues = "#{liveQueue.name}", concurrency = "1")
    public void receive(ChatMessage message) {
        log.debug("Live message {} for room {} received", message.id(), message.roomId());
        liveDelivery.deliver(message);
    }
}
