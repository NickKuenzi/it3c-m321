package ch.benedict.m321.chatservice.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.UUID;

/**
 * Legt beim Start alles an, was der Broker braucht.
 *
 * Spring meldet diese Beans beim Verbindungsaufbau an RabbitMQ. Existiert
 * eine Queue schon, passiert nichts — das Anlegen ist wiederholbar.
 */
@Configuration
public class RabbitConfig {

    /**
     * Der Schreibweg. "durable" heisst: die Queue überlebt einen Neustart
     * des Brokers. Was der Verbraucher endgültig ablehnt, landet über den
     * Standard-Exchange ("") in der Dead-Letter-Queue.
     */
    @Bean
    public Queue persistQueue() {
        return QueueBuilder.durable(QueueNames.PERSIST_QUEUE)
                .deadLetterExchange("")
                .deadLetterRoutingKey(QueueNames.DEAD_LETTER_QUEUE)
                .build();
    }

    /** Das Abstellgleis für Nachrichten, die niemand verarbeiten konnte. */
    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder.durable(QueueNames.DEAD_LETTER_QUEUE).build();
    }

    /**
     * Der Zustellweg. Fanout heisst: JEDE gebundene Queue bekommt eine
     * Kopie. Das ist genau richtig, weil jede chat-service-Instanz jede
     * Nachricht sehen muss — nur sie weiss, welche ihrer SSE-Clients
     * im betroffenen Raum sitzen.
     */
    @Bean
    public FanoutExchange deliveryExchange() {
        return new FanoutExchange(QueueNames.DELIVERY_EXCHANGE, true, false);
    }

    /**
     * Die Live-Queue dieser Instanz (Spezifikation 3.2, 3.3, 3.5, 4.2).
     *
     * - eigener Name pro Instanz: Der Fanout-Exchange kopiert in jede Queue,
     *   also bekommt jede Instanz jede Nachricht.
     * - exklusiv und Auto-Delete: Die Queue gehört nur der Verbindung dieser
     *   Instanz und wird gelöscht, sobald diese Verbindung zu ist (S4).
     * - nicht dauerhaft: Eine Live-Queue muss keinen Neustart des Brokers
     *   überleben. Wer etwas verpasst, holt es aus dem Zwischenspeicher nach.
     * - höchstens maxLength Nachrichten, die älteste fliegt raus: Eine
     *   hängende Instanz darf den Speicher von RabbitMQ nicht füllen (S6).
     * - TTL pro Nachricht: Eine Live-Nachricht, die so lange gewartet hat,
     *   ist nicht mehr «live».
     */
    @Bean
    public Queue liveQueue(@Value("${live-delivery.queue-max-length}") int maxLength,
                           @Value("${live-delivery.queue-message-ttl-ms}") int messageTtlMs) {
        String queueName = buildLiveQueueName();
        return QueueBuilder.nonDurable(queueName)
                .exclusive()
                .autoDelete()
                .maxLength(maxLength)
                .overflow(QueueBuilder.Overflow.dropHead)
                .ttl(messageTtlMs)
                .build();
    }

    /**
     * Hängt die Live-Queue an den Fanout-Exchange. Ohne diese Bindung
     * würde RabbitMQ die Kopie für diese Instanz einfach verwerfen.
     */
    @Bean
    public Binding liveQueueBinding(@Qualifier("liveQueue") Queue liveQueue,
                                    FanoutExchange deliveryExchange) {
        return BindingBuilder.bind(liveQueue).to(deliveryExchange);
    }

    /**
     * Baut einen Namen, den es nur einmal gibt: Rechnername plus ein
     * zufälliger Teil. Der Rechnername allein reicht nicht, denn zwei
     * Instanzen auf demselben Rechner (zum Beispiel im Test) hätten sonst
     * denselben Namen, und die zweite dürfte die exklusive Queue nicht anlegen.
     * Der Rechnername bleibt drin, damit man in rabbitmqctl sieht, welche
     * Queue zu welchem Container gehört.
     */
    private String buildLiveQueueName() {
        String hostName = readHostName();
        UUID randomId = UUID.randomUUID();
        String randomText = randomId.toString();
        String randomPart = randomText.substring(0, 8);
        return QueueNames.LIVE_QUEUE_PREFIX + hostName + "." + randomPart;
    }

    /**
     * Der Name des Rechners. Im Docker-Container ist das die Container-ID.
     * Lässt er sich nicht ermitteln, reicht ein fester Ersatz, weil der
     * zufällige Teil den Namen trotzdem eindeutig macht.
     */
    private String readHostName() {
        try {
            InetAddress localHost = InetAddress.getLocalHost();
            return localHost.getHostName();
        } catch (UnknownHostException exception) {
            return "unknown-host";
        }
    }

    /**
     * Nachrichten gehen als JSON über die Leitung, nicht als serialisiertes
     * Java-Objekt. Nur so kann später ein Dienst in einer anderen Sprache
     * mitlesen.
     *
     * Der ObjectMapper kommt von Spring Boot und kann bereits Instant im
     * ISO-8601-Format schreiben.
     */
    @Bean
    public MessageConverter jsonMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }
}
