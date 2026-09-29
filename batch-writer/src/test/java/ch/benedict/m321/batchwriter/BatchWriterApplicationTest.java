package ch.benedict.m321.batchwriter;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Prüft, dass der batch-writer mit einem echten RabbitMQ und einer echten
 * PostgreSQL hochfährt und dabei die Tabelle message richtig anlegt.
 *
 * Beide Dienste kommen per Testcontainers. So läuft "mvn test" auch dann, wenn
 * der Stack aus docker-compose nicht gestartet ist (Szenario S1).
 */
@SpringBootTest
@Testcontainers
class BatchWriterApplicationTest {

    /** Echter Broker, dieselbe Version wie in docker-compose.yml. */
    @Container
    @ServiceConnection
    static RabbitMQContainer rabbitMq = new RabbitMQContainer("rabbitmq:3.13-management");

    /** Echte Datenbank, dieselbe Version wie später in docker-compose.yml. */
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    /** Der Spring-Kontext startet ohne Fehler, also auch schema.sql läuft durch. */
    @Test
    void contextLoads() {
    }

    /**
     * Die Tabelle hat genau die sechs Spalten aus PLANUNG.md 3.7, in dieser
     * Reihenfolge und mit diesen Typen. Das Prüfskript der Lehrperson verlässt
     * sich auf genau diese Namen.
     */
    @Test
    void messageTableHasColumnsFromPlanning() {
        String sql = "SELECT column_name, data_type FROM information_schema.columns "
                + "WHERE table_name = 'message' ORDER BY ordinal_position";
        List<Map<String, Object>> columns = jdbcTemplate.queryForList(sql);

        assertEquals(6, columns.size());
        assertColumn(columns.get(0), "id", "uuid");
        assertColumn(columns.get(1), "room_id", "uuid");
        assertColumn(columns.get(2), "sender_id", "character varying");
        assertColumn(columns.get(3), "sender_name", "character varying");
        assertColumn(columns.get(4), "content", "text");
        assertColumn(columns.get(5), "sent_at", "timestamp with time zone");
    }

    /** Der Index für "die letzten Nachrichten eines Raums" existiert. */
    @Test
    void indexForReadingHistoryExists() {
        String sql = "SELECT count(*) FROM pg_indexes "
                + "WHERE tablename = 'message' AND indexname = 'idx_message_room_sent_at'";
        Integer indexCount = jdbcTemplate.queryForObject(sql, Integer.class);

        assertEquals(1, indexCount);
    }

    /**
     * Es gibt keinen Fremdschlüssel auf room. Ohne Raumverwaltung wäre room
     * leer, und jede Nachricht würde abgelehnt (Spezifikation 4.2).
     */
    @Test
    void messageTableHasNoForeignKey() {
        String sql = "SELECT count(*) FROM information_schema.table_constraints "
                + "WHERE table_name = 'message' AND constraint_type = 'FOREIGN KEY'";
        Integer foreignKeyCount = jdbcTemplate.queryForObject(sql, Integer.class);

        assertEquals(0, foreignKeyCount);
    }

    /**
     * schema.sql ein zweites Mal ausführen wirft keinen Fehler. Genau das
     * passiert bei jedem Neustart und bei einer zweiten Instanz (Szenario S6).
     */
    @Test
    void schemaCanBeAppliedTwice() {
        ClassPathResource schema = new ClassPathResource("schema.sql");
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(schema);

        // Wirft eine Ausnahme, falls eine Anweisung scheitert. Dann ist der Test rot.
        populator.execute(dataSource);
    }

    /** Vergleicht Name und Typ einer Spalte aus information_schema. */
    private void assertColumn(Map<String, Object> column, String expectedName, String expectedType) {
        Object actualName = column.get("column_name");
        Object actualType = column.get("data_type");

        assertEquals(expectedName, actualName);
        assertEquals(expectedType, actualType);
    }
}
