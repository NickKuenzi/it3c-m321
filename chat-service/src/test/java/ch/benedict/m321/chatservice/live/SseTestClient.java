package ch.benedict.m321.chatservice.live;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpHeaders;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Ein kleiner SSE-Client für die Tests. Er tut, was ein Browser mit
 * EventSource tut: GET /stream öffnen, die Zeilen lesen und daraus Ereignisse
 * bauen.
 *
 * Gelesen wird in einem eigenen Thread, weil der Stream nie von selbst endet.
 * Die Ereignisse landen in einer Liste, die der Test abfragen kann.
 */
public class SseTestClient {

    /** Wie lange höchstens auf Ereignisse gewartet wird, in Zehntelsekunden. */
    private static final int MAX_WAIT_TENTHS = 150;

    private final List<SseTestEvent> events = new ArrayList<>();

    private final HttpResponse<InputStream> response;

    /**
     * Verbindet sich und beginnt zu lesen. lastEventId darf null sein,
     * dann wird kein Last-Event-ID-Header mitgeschickt.
     */
    public SseTestClient(String url, String lastEventId) throws IOException, InterruptedException {
        URI address = URI.create(url);
        // Timeout für die Kopfzeilen: Kommt keine Antwort, soll der Test
        // scheitern statt ewig zu warten.
        Duration headerTimeout = Duration.ofSeconds(10);
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(address)
                .header("Accept", "text/event-stream")
                .timeout(headerTimeout)
                .GET();
        if (lastEventId != null) {
            requestBuilder.header("Last-Event-ID", lastEventId);
        }
        HttpRequest request = requestBuilder.build();
        HttpClient client = HttpClient.newHttpClient();

        // send kehrt zurück, sobald die Kopfzeilen da sind. Dann ist die
        // Verbindung im Server schon angemeldet.
        this.response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());

        Thread reader = new Thread(this::readEvents);
        reader.setDaemon(true);
        reader.start();
    }

    /** HTTP-Status der Antwort, zum Beispiel 200. */
    public int statusCode() {
        return response.statusCode();
    }

    /** Content-Type der Antwort, zum Beispiel text/event-stream. */
    public String contentType() {
        HttpHeaders headers = response.headers();
        Optional<String> contentType = headers.firstValue("Content-Type");
        return contentType.orElse("");
    }

    /** Eine Kopie der bisher empfangenen Ereignisse, in Empfangsreihenfolge. */
    public List<SseTestEvent> events() {
        synchronized (events) {
            return new ArrayList<>(events);
        }
    }

    /** Wartet, bis mindestens so viele Ereignisse da sind, höchstens 15 s. */
    public List<SseTestEvent> waitForEvents(int expectedCount) throws InterruptedException {
        for (int i = 0; i < MAX_WAIT_TENTHS; i++) {
            List<SseTestEvent> received = events();
            if (received.size() >= expectedCount) {
                return received;
            }
            Thread.sleep(100);
        }
        return events();
    }

    /** Trennt die Verbindung, wie ein Browser, der den Tab schliesst. */
    public void close() throws IOException {
        InputStream body = response.body();
        body.close();
    }

    /**
     * Liest Zeile für Zeile. Ein Ereignis besteht aus Zeilen wie «id:…»,
     * «event:…» und «data:…» und endet mit einer leeren Zeile. Zeilen, die
     * mit «:» beginnen, sind Kommentare und werden übersprungen.
     */
    private void readEvents() {
        InputStream body = response.body();
        InputStreamReader streamReader = new InputStreamReader(body, StandardCharsets.UTF_8);
        try (BufferedReader reader = new BufferedReader(streamReader)) {
            String id = null;
            String name = null;
            String data = null;
            String line = reader.readLine();
            while (line != null) {
                if (line.startsWith("id:")) {
                    id = line.substring(3);
                } else if (line.startsWith("event:")) {
                    name = line.substring(6);
                } else if (line.startsWith("data:")) {
                    data = line.substring(5);
                } else if (line.isEmpty() && (id != null || name != null || data != null)) {
                    addEvent(new SseTestEvent(id, name, data));
                    id = null;
                    name = null;
                    data = null;
                }
                line = reader.readLine();
            }
        } catch (IOException exception) {
            // Verbindung wurde geschlossen, mit close() oder vom Server. Fertig.
        }
    }

    /** Legt ein fertiges Ereignis in die Liste. */
    private void addEvent(SseTestEvent event) {
        synchronized (events) {
            events.add(event);
        }
    }

    /**
     * Ein empfangenes Ereignis.
     *
     * @param id   Inhalt der id:-Zeile, null wenn keine da war
     * @param name Inhalt der event:-Zeile, null bei normalen Nachrichten
     * @param data Inhalt der data:-Zeile
     */
    public record SseTestEvent(String id, String name, String data) {
    }
}
