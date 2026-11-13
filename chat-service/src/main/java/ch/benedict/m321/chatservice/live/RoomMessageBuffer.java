package ch.benedict.m321.chatservice.live;

import ch.benedict.m321.chatservice.dto.ChatMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Merkt sich pro Raum die letzten Nachrichten, damit ein Client nach einem
 * Abbruch nachholen kann, was ihm fehlt (Spezifikation 4.1, Szenario S7).
 *
 * Bewusst im Speicher und nicht in der Datenbank: Das Nachholen soll auch
 * funktionieren, wenn PostgreSQL gestoppt ist (S5), und jede Instanz bekommt
 * ohnehin jede Nachricht. Nach einem Neustart der Instanz ist der Speicher
 * leer, das ist eine bekannte Grenze (Spezifikation 7).
 *
 * Die Methoden sind synchronized: Der Verbraucher an der Live-Queue schreibt,
 * während gleichzeitig HTTP-Anfragen lesen. Ohne Sperre könnte ein Leser
 * eine halb veränderte Liste sehen.
 */
@Component
public class RoomMessageBuffer {

    /** Höchstens so viele Nachrichten pro Raum, danach fliegt die älteste raus. */
    private final int capacityPerRoom;

    /** Pro Raum eine Schlange: vorne die älteste, hinten die neueste Nachricht. */
    private final Map<UUID, ArrayDeque<ChatMessage>> messagesByRoom = new HashMap<>();

    /** Die Grösse kommt aus application.yml, damit sie an einer Stelle steht. */
    public RoomMessageBuffer(@Value("${live-delivery.buffer-size-per-room}") int capacityPerRoom) {
        this.capacityPerRoom = capacityPerRoom;
    }

    /**
     * Hängt eine Nachricht hinten an die Schlange ihres Raums. Ist die
     * Schlange danach zu lang, fällt vorne die älteste weg.
     */
    public synchronized void add(ChatMessage message) {
        UUID roomId = message.roomId();
        ArrayDeque<ChatMessage> roomMessages = messagesByRoom.get(roomId);
        if (roomMessages == null) {
            roomMessages = new ArrayDeque<>();
            messagesByRoom.put(roomId, roomMessages);
        }

        roomMessages.addLast(message);
        if (roomMessages.size() > capacityPerRoom) {
            roomMessages.removeFirst();
        }
    }

    /**
     * Liefert alle Nachrichten des Raums, die NACH der Nachricht mit lastId
     * eingegangen sind, in ihrer Reihenfolge.
     *
     * Wir laufen von der ältesten zur neuesten. Sobald wir lastId gefunden
     * haben, sammeln wir alles, was danach kommt. Wird lastId nie gefunden,
     * ist sie unbekannt: zu alt, aus einem anderen Raum oder nie gesendet.
     */
    public synchronized MissedMessages findMessagesAfter(UUID roomId, UUID lastId) {
        List<ChatMessage> messagesAfter = new ArrayList<>();
        ArrayDeque<ChatMessage> roomMessages = messagesByRoom.get(roomId);
        if (roomMessages == null) {
            return new MissedMessages(false, messagesAfter);
        }

        boolean lastIdFound = false;
        for (ChatMessage message : roomMessages) {
            if (lastIdFound) {
                messagesAfter.add(message);
            } else if (message.id().equals(lastId)) {
                lastIdFound = true;
            }
        }
        return new MissedMessages(lastIdFound, messagesAfter);
    }
}
