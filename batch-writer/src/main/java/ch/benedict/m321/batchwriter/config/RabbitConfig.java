package ch.benedict.m321.batchwriter.config;

import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Richtet beim Start die Queues und den Stapel-Empfang ein.
 *
 * Die Queues legt auch der chat-service an. Wir legen sie trotzdem selbst an,
 * damit es egal ist, welcher Dienst zuerst startet, und damit die Tests keinen
 * chat-service brauchen (Spezifikation 3.9).
 */
@Configuration
public class RabbitConfig {

    /**
     * Der Schreibweg, genau wie in RabbitConfig des chat-service: durable, und
     * was abgelehnt wird, geht über den Standard-Exchange "" nach chat.dlq.
     *
     * Weicht hier eine Eigenschaft ab, verweigert RabbitMQ das Anlegen mit
     * PRECONDITION_FAILED. Deshalb müssen beide Stellen gleich bleiben.
     */
    @Bean
    public Queue persistQueue() {
        return QueueBuilder.durable(QueueNames.PERSIST_QUEUE)
                .deadLetterExchange("")
                .deadLetterRoutingKey(QueueNames.DEAD_LETTER_QUEUE)
                .build();
    }

    /** Das Abstellgleis für Nachrichten, die sich nie verarbeiten lassen. */
    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder.durable(QueueNames.DEAD_LETTER_QUEUE).build();
    }

    /**
     * Die Fabrik für jeden @RabbitListener in diesem Dienst. Sie heisst genau
     * rabbitListenerContainerFactory, damit sie die Voreinstellung von Spring
     * Boot ersetzt.
     *
     * Hier wird aus einzelnen Nachrichten ein Stapel (Spezifikation 3.1, 4.4):
     * Die Listener-Methode bekommt eine Liste mit bis zu batchSize Nachrichten.
     * Kommt batchTimeoutMs lang keine neue, geht der Stapel auch unvollständig los.
     *
     * Der prefetch muss mindestens so gross sein wie der Stapel. Sonst gibt
     * RabbitMQ nie genug Nachrichten heraus, und kein Stapel wird je voll.
     *
     * MANUAL heisst: Der Verbraucher bestätigt selbst, und zwar erst, wenn die
     * Nachrichten in der Datenbank stehen.
     */
    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            @Value("${batch-writer.batch-size}") int batchSize,
            @Value("${batch-writer.batch-timeout-ms}") long batchTimeoutMs,
            @Value("${batch-writer.prefetch}") int prefetch) {

        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setBatchListener(true);
        factory.setConsumerBatchEnabled(true);
        factory.setBatchSize(batchSize);
        factory.setReceiveTimeout(batchTimeoutMs);
        factory.setPrefetchCount(prefetch);
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        return factory;
    }
}
