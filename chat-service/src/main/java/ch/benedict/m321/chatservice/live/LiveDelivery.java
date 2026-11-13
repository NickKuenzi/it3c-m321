package ch.benedict.m321.chatservice.live;

import ch.benedict.m321.chatservice.dto.ChatMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Die Stelle, an der Nachrichten und offene Verbindungen zusammenkommen
 * (Spezifikation 3.1, Schritte 6 bis 8).
 *
 * Sie merkt sich pro Raum die offenen SSE-Verbindungen. Kommt eine Nachricht,
 * legt sie sie in den Zwischenspeicher und schickt sie an genau die
 * Verbindungen ihres Raums (Raumfilter, Spezifikation 3.2).
 *
 * Alle öffentlichen Methoden sind synchronized, mit derselben Sperre: Der
 * Verbraucher an der Live-Queue verteilt, während HTTP-Anfragen Verbindungen
 * an- und abmelden. Schritt 5 braucht genau diese eine Sperre, damit Nachholen
 * und Anmelden ohne Lücke zusammen passieren (Spezifikation 3.6).
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class LiveDelivery {

    private final RoomMessageBuffer roomMessageBuffer;

    /** Pro Raum die Liste der offenen Verbindungen. */
    private final Map<UUID, List<SseEmitter>> connectionsByRoom = new HashMap<>();

    /**
     * Eine neue Nachricht aus der Live-Queue: erst in den Zwischenspeicher,
     * dann an alle Verbindungen ihres Raums. Verbindungen, bei denen das
     * Senden scheitert, sind tot und werden entfernt.
     */
    public synchronized void deliver(ChatMessage message) {
        roomMessageBuffer.add(message);

        UUID roomId = message.roomId();
        List<SseEmitter> roomConnections = connectionsByRoom.get(roomId);
        if (roomConnections == null) {
            return;
        }

        // Kopie, weil wir beim Durchlaufen tote Verbindungen entfernen.
        List<SseEmitter> connectionsToSend = new ArrayList<>(roomConnections);
        for (SseEmitter connection : connectionsToSend) {
            sendMessageOrRemove(roomId, connection, message);
        }
    }

    /**
     * Meldet eine neue Verbindung für einen Raum an. Ab jetzt bekommt sie live.
     *
     * Als Erstes geht ein SSE-Kommentar raus (eine Zeile, die mit «:»
     * beginnt). Clients ignorieren ihn. Er sorgt aber dafür, dass Spring die
     * Antwort sofort mit Status 200 und text/event-stream abschickt. Ohne ihn
     * schickt Spring die Kopfzeilen erst mit der ersten Nachricht, und ein
     * Client in einem leeren Raum wüsste nicht, ob er verbunden ist.
     */
    public synchronized void register(UUID roomId, SseEmitter connection) {
        List<SseEmitter> roomConnections = connectionsByRoom.get(roomId);
        if (roomConnections == null) {
            roomConnections = new ArrayList<>();
            connectionsByRoom.put(roomId, roomConnections);
        }
        roomConnections.add(connection);
        log.debug("Client connected to room {}, {} connections", roomId, roomConnections.size());

        SseEmitter.SseEventBuilder connected = SseEmitter.event().comment("connected");
        sendOrRemove(roomId, connection, connected);
    }

    /**
     * Meldet eine Verbindung ab, zum Beispiel weil der Client getrennt hat
     * (Spezifikation 3.7). Ist der Raum danach leer, fällt er aus der Liste;
     * sein Zwischenspeicher bleibt aber bestehen.
     */
    public synchronized void remove(UUID roomId, SseEmitter connection) {
        List<SseEmitter> roomConnections = connectionsByRoom.get(roomId);
        if (roomConnections == null) {
            return;
        }
        roomConnections.remove(connection);
        if (roomConnections.isEmpty()) {
            connectionsByRoom.remove(roomId);
        }
        log.debug("Client disconnected from room {}", roomId);
    }

    /**
     * Schickt eine Nachricht als SSE-Ereignis: id ist die Nachrichten-ID,
     * data das JSON der Nachricht (Spezifikation 2.2). Das JSON schreibt der
     * ObjectMapper von Spring, also dasselbe Format wie in chat.persist.
     *
     * Scheitert das Senden, ist der Client weg. Dann wird die Verbindung
     * entfernt, damit wir ihn nicht bei jeder Nachricht erneut versuchen.
     */
    private void sendMessageOrRemove(UUID roomId, SseEmitter connection, ChatMessage message) {
        String eventId = message.id().toString();
        SseEmitter.SseEventBuilder event = SseEmitter.event()
                .id(eventId)
                .data(message, MediaType.APPLICATION_JSON);
        sendOrRemove(roomId, connection, event);
    }

    /**
     * Schickt ein fertiges Ereignis an eine Verbindung. Scheitert das, ist
     * der Client weg, und die Verbindung wird entfernt.
     */
    private void sendOrRemove(UUID roomId, SseEmitter connection, SseEmitter.SseEventBuilder event) {
        try {
            connection.send(event);
        } catch (IOException | IllegalStateException exception) {
            log.debug("Sending to client in room {} failed, removing connection: {}",
                    roomId, exception.getMessage());
            remove(roomId, connection);
        }
    }
}
