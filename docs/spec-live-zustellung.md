# Spezifikation: Live-Zustellung

## 1. Zweck und Abgrenzung

Ziel ist es, eine Live-Zustellung für den Chat zu bauen. Wenn ein Client eine Nachricht sendet, sollen alle Clients im gleichen Raum diese Nachricht möglichst sofort erhalten.

Nicht Bestandteil dieser Aufgabe sind Keycloak, das web-gateway, React, JavaFX sowie die Verwaltung von Räumen und Mitgliedern. Es geht ausschliesslich um die zuverlässige Verteilung der Nachrichten an die verbundenen Clients.

## 2. Vertrag

### 2.1 Was am Exchange ankommt

Der Exchange heisst `chat.delivery`. Er ist vom Typ `fanout` und dauerhaft (durable).

Der Name ist in `chat-service/.../config/QueueNames.java`, Zeile 16, definiert. Angelegt wird der Exchange in `chat-service/.../config/RabbitConfig.java`, Zeile 47–48.

Die Nachrichten werden in `chat-service/.../service/MessagePublisher.java` veröffentlicht:

- Zeile 35: `convertAndSend(QueueNames.PERSIST_QUEUE, message)` sendet die Nachricht an `chat.persist`.
- Zeile 36: `convertAndSend(QueueNames.DELIVERY_EXCHANGE, "", message)` sendet dieselbe Nachricht an den Exchange `chat.delivery`.

Beide Aufrufe verwenden dasselbe Objekt `message` und denselben JSON-Umwandler. Dieser ist in `RabbitConfig.java`, Zeile 61, als `Jackson2JsonMessageConverter` definiert.

Eine Chat-Nachricht enthält folgende Felder:

- `id`
- `roomId`
- `senderId`
- `senderName`
- `content`
- `sentAt`

Diese Felder sind im DTO `ChatMessage.java` definiert.

### 2.2 Was am Stream hinausgeht

Ein Client verbindet sich über `GET /stream?roomId=...` mit dem Server.

Die Verbindung verwendet Server-Sent Events (SSE). Der Content-Type lautet `text/event-stream`.

Jedes normale Ereignis enthält:

- `id:` mit der eindeutigen Nachrichten-ID.
- `data:` mit genau dem JSON der Chat-Nachricht aus Abschnitt 2.1.

Fehlt die `roomId` oder ist sie keine gültige UUID, antwortet der Server mit HTTP `400 Bad Request`.

### 2.3 Last-Event-ID

Wenn ein Client keine `Last-Event-ID` mitsendet, handelt es sich um eine neue Verbindung. In diesem Fall werden keine alten Nachrichten nachgeliefert. Der Client erhält nur neue Nachrichten ab dem Zeitpunkt der Verbindung.

Anhand der `Last-Event-ID` wird die letzte empfangene Nachricht gesucht. Anschliessend werden alle Nachrichten danach zugestellt.

Wenn die `Last-Event-ID` nicht mehr im Zwischenspeicher vorhanden ist, kann der Server nicht mehr feststellen, welche Nachrichten fehlen. Deshalb sendet er ein spezielles `resync`-Ereignis. Danach läuft die normale Live-Zustellung weiter. Der Client weiss dadurch, dass möglicherweise Nachrichten fehlen.

Das `resync`-Ereignis bekommt keine eigene `id:`, weil es keine normale Chat-Nachricht ist. Es soll nicht als zuletzt empfangene Chat-Nachricht gespeichert werden. Dadurch wird verhindert, dass der Client bei einer erneuten Verbindung eine falsche Nachrichtenposition verwendet.

## 3. Verhalten

### 3.1 Normalfall

Der Weg einer Nachricht vom Senden bis zur Anzeige funktioniert folgendermassen:

1. Ein Client sendet eine Nachricht über den POST-Endpunkt.
2. Der Chat-Service nimmt die Nachricht entgegen.
3. Die Nachricht wird an den RabbitMQ-Exchange weitergegeben.
4. RabbitMQ verteilt die Nachricht an die entsprechenden Queues.
5. Die jeweilige chat-service-Instanz empfängt die Nachricht über ihre eigene Queue.
6. Die Nachricht wird im In-Memory-Zwischenspeicher des entsprechenden Raums abgelegt.
7. Der Server prüft, welche Clients mit diesem Raum verbunden sind.
8. Die Nachricht wird nur an diese Clients über den SSE-Stream zugestellt.
9. Der Client empfängt die Nachricht und zeigt sie auf dem Bildschirm an.

Die Speicherung und die Live-Zustellung sind voneinander getrennte Vorgänge.

### 3.2 Zwei Instanzen (S3)

Jede chat-service-Instanz benötigt eine eigene Queue.

RabbitMQ verteilt die Nachrichten über den Fanout-Exchange `chat.delivery` an jede vorhandene Queue. Dadurch erhält jede Instanz eine eigene Kopie jeder Nachricht.

Eine gemeinsame Queue wäre ungeeignet, weil RabbitMQ die Nachrichten dann auf die verschiedenen Instanzen verteilen würde. Dadurch würde nicht jede Instanz jede Nachricht erhalten.

Jede Instanz erhält alle Nachrichten, verschickt diese aber nur an die Clients, die mit derselben `roomId` verbunden sind.

Die Reihenfolge der Nachrichten eines Absenders bleibt aus drei Gründen erhalten:

1. Die Nachrichten eines Absenders werden über eine chat-service-Instanz nacheinander veröffentlicht.
2. Eine Queue gibt die Nachrichten in derselben Reihenfolge weiter, in der sie angekommen sind.
3. Pro Instanz verarbeitet ein einzelner Verbraucher die Nachrichten nacheinander.

**Vorgabe für die Umsetzung:** Pro Instanz darf nur ein Verbraucher die Live-Nachrichten verarbeiten, damit die Reihenfolge erhalten bleibt.

### 3.3 Instanz verschwindet (S4)

Jede Instanz besitzt eine eigene exklusive Queue mit Auto-Delete.

Wenn die Verbindung einer Instanz zu RabbitMQ geschlossen wird, wird ihre exklusive Queue automatisch gelöscht.

Die Queue-Lebensdauer ist unabhängig von der TTL der Nachrichten.

Die TTL beträgt 30 Sekunden pro Nachricht (`x-message-ttl`). Eine Nachricht wird also entfernt, wenn sie länger als 30 Sekunden in der Queue liegt.

Dadurch bleiben keine unnötigen Queues zurück und alte Nachrichten werden nicht unbegrenzt aufbewahrt.

### 3.4 batch-writer und Datenbank weg (S5)

Die Live-Zustellung funktioniert auch dann weiter, wenn die Datenbank nicht verfügbar ist.

Der Grund dafür ist, dass die Live-Zustellung und die dauerhafte Speicherung voneinander getrennt sind.

Der batch-writer ist für die Speicherung der Nachrichten zuständig. Der chat-service benötigt dagegen keine direkte Datenbankverbindung, um Nachrichten live zuzustellen.

Dadurch kann der Chat weiterhin Nachrichten verteilen, auch wenn die Datenbank gestoppt ist.

### 3.5 Instanz hängt (S6)

Wenn die Queue voll ist, werden die ältesten Nachrichten entfernt, damit die neuesten Nachrichten gespeichert werden können.

Das ist sinnvoll, weil ein Client nach einer Pause möglichst aktuelle Nachrichten erhalten soll. Ausserdem können ältere Nachrichten durch die TTL von 30 Sekunden ohnehin bereits abgelaufen sein.

Die Queue wird auf maximal 1000 Nachrichten begrenzt. Diese Grenze verhindert, dass RabbitMQ bei einer nicht reagierenden Instanz unbegrenzt Speicher verbraucht.

Bei `chat.live` dürfen ältere Nachrichten entfernt werden, weil es sich um eine Live-Zustellung handelt und die neuesten Nachrichten wichtiger sind.

Bei `chat.persist` dürfen Nachrichten dagegen nicht einfach entfernt werden, weil diese für die dauerhafte Speicherung vorgesehen sind.

Wenn eine Instanz beispielsweise mit `docker compose pause` angehalten wird, verarbeitet sie keine Nachrichten mehr. Sie antwortet auch nicht mehr auf die regelmässigen RabbitMQ-Heartbeats.

Nach ungefähr 60 Sekunden kann RabbitMQ die Verbindung schliessen. Dadurch wird die exklusive Queue gelöscht.

Wird die Instanz später mit `docker compose unpause` fortgesetzt, verbindet sie sich erneut mit RabbitMQ und erstellt eine neue Queue.

Die Live-Zustellung funktioniert danach wieder. Allerdings fehlen im In-Memory-Zwischenspeicher dieser Instanz die Nachrichten, die während des Ausfalls nicht empfangen wurden.

### 3.6 Client trennt und verbindet neu (S7)

Die Nachrichten werden in der Reihenfolge ihres Eingangs gespeichert. Anhand der `Last-Event-ID` wird die letzte empfangene Nachricht gesucht. Anschliessend werden alle Nachrichten danach zugestellt.

Da jede Instanz jede Nachricht erhält, kann ein Client auch nach einem Wechsel auf eine andere Instanz seine verpassten Nachrichten nachholen.

Das Nachholen der Nachrichten und die Anmeldung für neue Live-Nachrichten müssen als ein gemeinsamer, gesperrter Vorgang ablaufen.

Dadurch wird verhindert, dass während des Nachholens neue Nachrichten eintreffen und verloren gehen oder in der falschen Reihenfolge zugestellt werden.

### 3.7 Client trennt endgültig

Wenn ein Client die Verbindung beendet, erkennt der Server die geschlossene SSE-Verbindung.

Die Verbindung wird aus der Liste der aktiven Verbindungen entfernt. Dadurch werden keine weiteren Nachrichten an diesen Client gesendet.

Der Zwischenspeicher des Raums bleibt bestehen, auch wenn kein Client mehr verbunden ist. So können spätere Verbindungen Nachrichten nachholen.

## 4. Queues und Zwischenspeicher

### 4.1 Zwischenspeicher

In-Memory-Zwischenspeicher mit maximal 100 Nachrichten pro Raum.

100 Nachrichten bieten genügend Reserve für den Test mit 50 Nachrichten. Ein Zwischenspeicher benötigt keine zusätzliche Datenbankverbindung. Da jede Instanz jede Nachricht erhält, kann ein Client auch nach einem Wechsel auf eine andere Instanz seine verpassten Nachrichten nachholen.

Die Nachrichten werden in der Reihenfolge ihres Eingangs gespeichert. Anhand der `Last-Event-ID` wird die letzte empfangene Nachricht gesucht. Anschliessend werden alle Nachrichten danach zugestellt.

Grenze: Bei einem Neustart der Instanz geht der Zwischenspeicher verloren.

Jede ankommende Nachricht wird im Zwischenspeicher abgelegt, auch wenn gerade kein Client im Raum verbunden ist. Dadurch können Clients, die sich später verbinden, die Nachrichten seit ihrer letzten Verbindung nachholen.

### 4.2 Queues

| Name | Wer legt sie an? | Lebensdauer | Grenze | Bei Erreichen der Grenze |
|---|---|---|---|---|
| `chat.live.<instanz>` | Jede chat-service-Instanz | Exklusiv und Auto-Delete; wird bei geschlossener Verbindung gelöscht | 1000 Nachrichten | Älteste Nachrichten werden entfernt |
| `chat.persist` | chat-service und batch-writer | Dauerhaft | Keine | Keine Begrenzung |
| `chat.dlq` | RabbitMQ-Konfiguration | Dauerhaft | Keine | Keine Begrenzung |

Für `chat.live.<instanz>` gilt zusätzlich eine Nachrichten-TTL von 30 Sekunden (`x-message-ttl`). Nachrichten, die länger als 30 Sekunden in der Queue liegen, verfallen.

Der Platzhalter `<instanz>` steht für die jeweilige Kennung der chat-service-Instanz. Dadurch besitzt jede Instanz eine eigene Queue.

### 4.3 Begründung der unterschiedlichen Queues

Die Queues haben unterschiedliche Aufgaben.

`chat.live` ist für die schnelle Live-Zustellung zuständig. Hier sind aktuelle Nachrichten wichtiger als ältere Nachrichten.

`chat.persist` ist für die dauerhafte Speicherung zuständig. Deshalb dürfen dort keine Nachrichten einfach entfernt werden.

`chat.dlq` dient dazu, Nachrichten aufzubewahren, die nicht erfolgreich verarbeitet werden konnten. Sie können später untersucht werden.

## 5. Konfiguration

Die folgenden Werte werden zentral in der `application.yml` konfiguriert:

| Einstellung | Wert | Zweck |
|---|---:|---|
| Maximale Queue-Grösse | 1000 Nachrichten | Verhindert unbegrenztes Wachstum |
| Queue-Nachrichten-TTL | 30 Sekunden | Entfernt zu lange wartende Nachrichten |
| Zwischenspeicher pro Raum | 100 Nachrichten | Ermöglicht das Nachholen verpasster Nachrichten |

Es werden keine neuen Variablen in `.env.example` benötigt, da diese Werte keine Geheimnisse enthalten und für alle Instanzen gleich sind.

## 6. Abnahmekriterien

### S1 – Automatisierte Tests

**Ablauf:** Das Projekt wird bereinigt und die automatisierten Tests werden ausgeführt.

**Befehl:**

```
mvn clean test
```

**Bestanden, wenn:** Der Build erfolgreich ist und alle Tests grün durchlaufen.

### S2 – Frischer Klon und Docker-Start

**Ablauf:** Das Repository wird frisch geklont. Anschliessend werden alle Services mit Docker Compose gestartet.

**Befehl:**

```
docker compose up -d --build
```

Zusätzlich werden die Container mit dem in Bewertung 1 vorgegebenen Befehl überprüft.

**Bestanden, wenn:** Alle benötigten Services laufen und kein zusätzlicher Port auf dem Host geöffnet werden muss.

### S3 – Zwei Instanzen, Raumtrennung und Reihenfolge

**Ablauf:** Es werden zwei chat-service-Instanzen gestartet. An jeder Instanz wird ein Client für Raum A verbunden. Ein dritter Client wird mit Raum B verbunden. Anschliessend werden je 200 Nachrichten über jede Instanz gesendet.

**Befehle:**

Client an Instanz 1:

```
docker run --rm --network chat-net curlimages/curl -N -s "http://it3c-m321-chat-service-1:8080/stream?roomId=<A>"
```

Client an Instanz 2:

```
docker run --rm --network chat-net curlimages/curl -N -s "http://it3c-m321-chat-service-2:8080/stream?roomId=<A>"
```

Client für Raum B:

```
docker run --rm --network chat-net curlimages/curl -N -s "http://it3c-m321-chat-service-1:8080/stream?roomId=<B>"
```

Die Nachrichten werden mit dem angepassten Sendeskript gezielt über beide Instanzen verschickt. Die Anzahl der empfangenen Nachrichten kann beispielsweise mit `grep -c "^id:"` gezählt werden.

**Bestanden, wenn:** Beide Clients in Raum A jeweils genau 400 Nachrichten erhalten. Jede Nachricht kommt genau einmal an. Die Reihenfolge pro Absender bleibt erhalten. Der Client in Raum B erhält 0 Nachrichten.

### S4 – Verhalten beim Entfernen einer Instanz

**Ablauf:** Zuerst werden zwei chat-service-Instanzen gestartet. Danach wird eine Instanz heruntergefahren. Nach 30 Sekunden wird die Queue-Situation erneut überprüft.

**Befehle:**

Bindings anzeigen:

```
docker compose exec rabbitmq rabbitmqctl list_bindings source_name destination_name
```

Queues und Verbraucher anzeigen:

```
docker compose exec rabbitmq rabbitmqctl list_queues name messages consumers
```

**Bestanden, wenn:** Vor dem Herunterfahren bestehen genau zwei Queues am Exchange `chat.delivery`, jede mit genau einem Verbraucher. Nach 30 Sekunden besteht nur noch eine Queue am Exchange. Die Queues `chat.persist` und `chat.dlq` bleiben bestehen.

### S5 – Ausfall von batch-writer und PostgreSQL

**Ablauf:** `batch-writer` und PostgreSQL werden gestoppt. Danach werden 100 Nachrichten gesendet. Die Nachrichten werden beim Client überprüft. Anschliessend werden beide Services wieder gestartet und die Datenbank wird kontrolliert.

**Befehle:**

```
docker compose stop batch-writer postgres
```

Nach dem Senden der 100 Nachrichten wird die Anzahl beim Client überprüft. Die Nachrichten müssen innerhalb von 5 Sekunden eintreffen.

Services wieder starten:

```
docker compose start postgres batch-writer
```

Anzahl gespeicherter Nachrichten überprüfen:

```
docker compose exec postgres psql -U chat -d chat -tAc "SELECT count(*) FROM message WHERE content LIKE 'S5-%'"
```

**Bestanden, wenn:** Der Client innerhalb von 5 Sekunden alle 100 Nachrichten erhält. Nach dem Neustart befinden sich alle 100 Nachrichten in der Datenbanktabelle.

### S6 – Ausfall und Überlastung einer Instanz

**Ablauf:** Eine der beiden chat-service-Instanzen wird pausiert. Währenddessen werden 5000 Nachrichten gesendet. Die Queue der pausierten Instanz wird kontrolliert. Danach wird die Instanz fortgesetzt.

**Befehle:**

Instanz 2 pausieren:

```
docker pause it3c-m321-chat-service-2
```

Queues überprüfen:

```
docker compose exec rabbitmq rabbitmqctl list_queues name messages consumers
```

Instanz 2 fortsetzen:

```
docker unpause it3c-m321-chat-service-2
```

**Bestanden, wenn:** Die Queue der pausierten Instanz zu keinem Zeitpunkt mehr als 1000 Nachrichten enthält. Der Client an der anderen Instanz erhält alle 5000 Nachrichten. Nach dem Fortsetzen der pausierten Instanz funktioniert die Zustellung wieder.

### S7 – Wiederverbindung mit Last-Event-ID

**Ablauf:** Ein Client verbindet sich mit dem SSE-Endpunkt und erhält eine Nachricht. Danach wird die Verbindung getrennt. Während der Trennung werden 50 weitere Nachrichten gesendet. Nach 10 Sekunden verbindet sich der Client mit der zuletzt empfangenen Nachrichten-ID erneut.

**Befehl:** Verbindung mit der zuletzt empfangenen ID wiederherstellen:

```
docker run --rm --network chat-net curlimages/curl -N -s -H "Last-Event-ID: <letzte-id>" "http://it3c-m321-chat-service-1:8080/stream?roomId=<A>"
```

Die Anzahl der nachgelieferten Nachrichten kann mit `grep -c "^id:"` überprüft werden.

**Bestanden, wenn:** Der Client genau die 50 während der Trennung gesendeten Nachrichten in der richtigen Reihenfolge erhält. Es gibt keine doppelten Nachrichten. Nach der Nachlieferung funktioniert die Live-Zustellung weiter.

### S8 – Quelltext und Codequalität

**Ablauf:** Der Quelltext im Verzeichnis `chat-service/` wird überprüft. Dabei werden Streams, Kommentare und die Repository-Inhalte kontrolliert.

**Befehl:** Die Prüf- und Suchbefehle aus Bewertung 1 werden verwendet.

**Bestanden, wenn:** Im Quelltext von `chat-service/` keine Streams vorhanden sind. Über jeder Klasse und jeder Methode befindet sich ein Kommentar. Die Datei `.env` befindet sich nicht im Repository.

## 7. Offene Punkte

- Der In-Memory-Zwischenspeicher geht bei einem Neustart einer Instanz verloren.
- Wenn eine Instanz länger ausfällt und ihre exklusive Queue gelöscht wird, fehlen ihr die Nachrichten während dieses Zeitraums. Nach dem Neustart entsteht eine neue Queue.
- Es gibt keinen separaten Endpunkt, um einen vollständigen Nachrichtenverlauf abzurufen.
- Wenn eine `Last-Event-ID` unbekannt ist, sendet der Server ein `resync`-Ereignis. Eine vollständige Wiederherstellung der fehlenden Nachrichten ist nicht möglich.
- Bei einer neuen Verbindung ohne `Last-Event-ID` werden keine alten Nachrichten nachgeliefert.
- Der Zwischenspeicher ist auf 100 Nachrichten pro Raum begrenzt. Ältere Nachrichten können deshalb nicht unbegrenzt nachgeholt werden.
- Für die korrekte Reihenfolge muss pro Instanz genau ein Verbraucher die Live-Nachrichten nacheinander verarbeiten.
