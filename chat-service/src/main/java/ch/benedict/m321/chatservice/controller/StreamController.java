package ch.benedict.m321.chatservice.controller;

import ch.benedict.m321.chatservice.live.LiveDelivery;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

/**
 * Der Anzeigeweg nach aussen: GET /stream?roomId=… hält eine Verbindung
 * offen, über die der Server neue Nachrichten des Raums schickt
 * (Server-Sent Events, Spezifikation 2.2).
 */
@RestController
@RequiredArgsConstructor
public class StreamController {

    /** 0 heisst: kein Zeitlimit. Eine Live-Verbindung soll offen bleiben. */
    private static final long NO_TIMEOUT = 0L;

    private final LiveDelivery liveDelivery;

    /**
     * Öffnet den Stream für einen Raum.
     *
     * roomId als UUID: Fehlt sie oder ist sie keine UUID, antwortet Spring
     * selbst mit 400 Bad Request, noch bevor diese Methode läuft
     * (Spezifikation 2.2).
     *
     * Die Verbindung meldet sich selbst ab, wenn sie endet, abläuft oder
     * einen Fehler hat (Spezifikation 3.7).
     */
    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam UUID roomId) {
        SseEmitter connection = new SseEmitter(NO_TIMEOUT);
        connection.onCompletion(() -> liveDelivery.remove(roomId, connection));
        connection.onTimeout(() -> liveDelivery.remove(roomId, connection));
        connection.onError(error -> liveDelivery.remove(roomId, connection));

        liveDelivery.register(roomId, connection);
        return connection;
    }
}
