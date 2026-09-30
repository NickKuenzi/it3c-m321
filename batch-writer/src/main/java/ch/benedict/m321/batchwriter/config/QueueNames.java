package ch.benedict.m321.batchwriter.config;

/**
 * Die Namen der Queues an genau einer Stelle.
 *
 * Sie müssen exakt so heissen wie in QueueNames des chat-service. Ein
 * Tippfehler würde nicht auffallen, der batch-writer würde einfach an einer
 * leeren Queue warten.
 */
public final class QueueNames {

    /** Schreibweg: Hier legt der chat-service ab, hier holt der batch-writer ab. */
    public static final String PERSIST_QUEUE = "chat.persist";

    /** Dead Letter: Hierhin leitet RabbitMQ, was der batch-writer ablehnt. */
    public static final String DEAD_LETTER_QUEUE = "chat.dlq";

    /** Diese Klasse ist eine reine Namenssammlung und wird nie erzeugt. */
    private QueueNames() {
    }
}
