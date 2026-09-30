package ch.benedict.m321.batchwriter.message;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.CannotGetJdbcConnectionException;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prüft die Unterscheidung «Datenbank weg» gegen «Datenbank lehnt ab».
 * Ein Fehler hier hiesse: Der batch-writer wartet ewig auf etwas, das nie
 * besser wird, oder er gibt bei einem Ausfall zu früh auf.
 *
 * Reiner Test ohne Container. Die Ausnahmen bauen wir so, wie Treiber,
 * Verbindungspool und Spring sie liefern.
 */
class DatabaseErrorsTest {

    /** SQLState 08001: Verbindung konnte nicht aufgebaut werden. */
    @Test
    void connectionStateIsConnectionProblem() {
        SQLException error = new SQLException("Connection refused", "08001");

        boolean result = DatabaseErrors.isConnectionProblem(error);

        assertTrue(result);
    }

    /** SQLState 23505: doppelter Schlüssel. Die Datenbank ist da, Warten hilft nicht. */
    @Test
    void uniqueViolationIsNoConnectionProblem() {
        SQLException error = new SQLException("duplicate key value", "23505");

        boolean result = DatabaseErrors.isConnectionProblem(error);

        assertFalse(result);
    }

    /** So meldet der Verbindungspool, dass er nach 5 s keine Verbindung bekam. */
    @Test
    void poolTimeoutIsConnectionProblem() {
        SQLTransientConnectionException error =
                new SQLTransientConnectionException("Connection is not available, request timed out");

        boolean result = DatabaseErrors.isConnectionProblem(error);

        assertTrue(result);
    }

    /** Spring verpackt den Fehler. Er muss trotzdem erkannt werden. */
    @Test
    void wrappedBySpringIsConnectionProblem() {
        SQLException cause = new SQLException("Connection refused", "08001");
        CannotGetJdbcConnectionException error =
                new CannotGetJdbcConnectionException("Failed to obtain JDBC Connection", cause);

        boolean result = DatabaseErrors.isConnectionProblem(error);

        assertTrue(result);
    }
}
