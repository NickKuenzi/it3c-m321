package ch.benedict.m321.batchwriter.message;

import ch.benedict.m321.batchwriter.dto.ChatMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Schreibt Nachrichten in die Tabelle message. Der batch-writer ist der
 * einzige Dienst, der das tut.
 *
 * Ein ganzer Stapel geht mit EINER INSERT-Anweisung in die Datenbank, nicht
 * mit einer Anweisung pro Nachricht. Das ist der Kern der Aufgabe: 1'667
 * Nachrichten pro Sekunde einzeln zu schreiben, hält keine Datenbank auf
 * Dauer aus (Spezifikation 1, 3.1, 4.4).
 */
@Repository
@Slf4j
@RequiredArgsConstructor
public class MessageWriter {

    /** Anfang der Anweisung. Die Zeilen werden dahinter angehängt. */
    private static final String INSERT_START =
            "INSERT INTO message (id, room_id, sender_id, sender_name, content, sent_at) VALUES ";

    /** Platzhalter für eine Zeile: ein Fragezeichen pro Spalte. */
    private static final String ROW_PLACEHOLDER = "(?, ?, ?, ?, ?, ?)";

    /**
     * Steht die id schon in der Tabelle, wird die Zeile ohne Fehler übersprungen.
     * Das macht erneut zugestellte Nachrichten harmlos (Spezifikation 3.2).
     */
    private static final String INSERT_END = " ON CONFLICT (id) DO NOTHING";

    private final JdbcTemplate jdbcTemplate;

    /**
     * Schreibt alle Nachrichten mit einer einzigen INSERT-Anweisung.
     *
     * Eine einzelne Anweisung ist in PostgreSQL immer genau eine Transaktion:
     * Danach stehen entweder alle Zeilen in der Tabelle oder keine.
     *
     * @return wie viele Zeilen neu eingefügt wurden. Die Differenz zur Anzahl
     *         Nachrichten sind Duplikate.
     */
    public int writeBatch(List<ChatMessage> messages) {
        // Ohne Zeilen wäre die Anweisung ungültiges SQL.
        if (messages.isEmpty()) {
            return 0;
        }

        String sql = buildInsertStatement(messages.size());
        Object[] parameters = collectParameters(messages);

        int insertedRows = jdbcTemplate.update(sql, parameters);

        log.debug("Inserted {} of {} messages with one statement", insertedRows, messages.size());
        return insertedRows;
    }

    /**
     * Baut die Anweisung mit so vielen Zeilen "(?, ?, ?, ?, ?, ?)" wie nötig.
     * Im SQL-Text stehen nur Fragezeichen, nie Werte. So ist SQL-Injection
     * ausgeschlossen, egal was in einer Nachricht steht.
     */
    private String buildInsertStatement(int rowCount) {
        StringBuilder sql = new StringBuilder(INSERT_START);

        for (int i = 0; i < rowCount; i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append(ROW_PLACEHOLDER);
        }

        sql.append(INSERT_END);
        return sql.toString();
    }

    /**
     * Legt die Werte aller Nachrichten der Reihe nach in ein Array, in genau
     * der Reihenfolge der Fragezeichen: pro Nachricht sechs Werte.
     *
     * sentAt geht als OffsetDateTime in UTC hinein, weil der PostgreSQL-Treiber
     * Instant nicht direkt kennt. Der Zeitpunkt bleibt derselbe.
     */
    private Object[] collectParameters(List<ChatMessage> messages) {
        List<Object> parameters = new ArrayList<>();

        for (ChatMessage message : messages) {
            OffsetDateTime sentAt = OffsetDateTime.ofInstant(message.sentAt(), ZoneOffset.UTC);
            parameters.add(message.id());
            parameters.add(message.roomId());
            parameters.add(message.senderId());
            parameters.add(message.senderName());
            parameters.add(message.content());
            parameters.add(sentAt);
        }

        return parameters.toArray();
    }
}
