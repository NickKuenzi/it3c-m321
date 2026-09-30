package ch.benedict.m321.batchwriter.message;

/**
 * Eine Nachricht, die sich nie verarbeiten lässt: kein gültiges JSON oder ein
 * Feld fehlt.
 *
 * Eigene Ausnahme, damit der Verbraucher diesen Fall klar von einem
 * Datenbankausfall unterscheiden kann. Eine ungültige Nachricht geht sofort
 * nach chat.dlq, bei einem Ausfall wird gewartet (Spezifikation 3.5, 3.6).
 */
public class InvalidMessageException extends RuntimeException {

    /** Ausnahme mit dem Grund, warum die Nachricht ungültig ist. */
    public InvalidMessageException(String reason) {
        super(reason);
    }

    /** Ausnahme mit Grund und dem ursprünglichen Lesefehler von Jackson. */
    public InvalidMessageException(String reason, Throwable cause) {
        super(reason, cause);
    }
}
