package ch.benedict.m321.batchwriter.message;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;

/**
 * Unterscheidet zwei Arten von Datenbankfehlern, die völlig anders behandelt
 * werden müssen (Spezifikation 3.5):
 *
 * - Die Datenbank ist nicht erreichbar. Das geht vorbei, also warten und
 *   denselben Stapel nochmals versuchen.
 * - Die Datenbank ist da, lehnt aber etwas ab. Da hilft Warten nicht.
 *
 * Spring verpackt den eigentlichen Fehler oft in eigene Ausnahmen, etwa
 * CannotGetJdbcConnectionException. Deshalb schauen wir nicht nur auf den
 * äussersten Fehler, sondern auf die ganze Kette der Ursachen.
 */
public final class DatabaseErrors {

    /** SQLState-Klasse «connection exception» laut SQL-Standard. */
    private static final String CONNECTION_STATE_PREFIX = "08";

    /** Nur statische Methoden. Ein Objekt davon braucht niemand. */
    private DatabaseErrors() {
    }

    /**
     * true, wenn der Fehler bedeutet: keine Verbindung zur Datenbank.
     *
     * Geht von aussen nach innen durch die Ursachen. Treffer ist eine
     * SQLTransientConnectionException (so meldet der Verbindungspool, dass er
     * nach 5 s keine Verbindung bekam) oder eine SQLException, deren SQLState
     * mit 08 beginnt (so meldet der PostgreSQL-Treiber eine abgebrochene oder
     * abgelehnte Verbindung).
     */
    public static boolean isConnectionProblem(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof SQLTransientConnectionException) {
                return true;
            }
            if (current instanceof SQLException sqlException) {
                String sqlState = sqlException.getSQLState();
                if (isConnectionState(sqlState)) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * Prüft den fünfstelligen SQLState. Die ersten zwei Zeichen sind die Klasse.
     * Nicht jede Ausnahme hat einen SQLState, deshalb der Test auf null.
     */
    private static boolean isConnectionState(String sqlState) {
        if (sqlState == null) {
            return false;
        }
        return sqlState.startsWith(CONNECTION_STATE_PREFIX);
    }
}
