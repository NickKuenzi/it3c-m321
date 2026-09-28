# Spezifikation batch-writer

Schritt 4 der Umsetzungsreihenfolge in `PLANUNG.md` («Persistenz»), Bewertung 1 im Modul M321.

Diese Spezifikation beschreibt, **was** der `batch-writer` tut und **warum**. Wie er Schritt für
Schritt gebaut wird, steht in `docs/plan-batch-writer.md`.

---

## 1. Zweck und Abgrenzung

### Zweck

Der `chat-service` legt jede angenommene Nachricht in die Queue `chat.persist`. Ohne Verbraucher
bleibt sie dort liegen. Startet RabbitMQ neu oder läuft die Queue voll, ist der Chat-Verlauf weg.

Der `batch-writer` holt die Nachrichten aus `chat.persist` und legt sie **dauerhaft** in der
Tabelle `message` in PostgreSQL ab. Er ist der **einzige** Dienst, der in diese Tabelle schreibt.

Er schreibt **gebündelt**: viele Nachrichten in einer einzigen Transaktion statt einer
Transaktion pro Nachricht. `PLANUNG.md` 4.1 rechnet mit 1'667 Nachrichten pro Sekunde. So viele
einzelne Transaktionen hält die Datenbank nicht auf Dauer aus. Mit Stapeln von 500 sind es noch
rund 3,3 Transaktionen pro Sekunde.

### Garantie

**At-least-once, ohne sichtbare Duplikate.** Eine Nachricht wird RabbitMQ erst bestätigt, wenn sie
in der Datenbank committet ist. Dadurch geht nichts verloren, aber dieselbe Nachricht kann zweimal
ankommen. Die Tabelle verwirft das zweite Exemplar (Abschnitt 4.1). Exactly-once wird ausdrücklich
**nicht** behauptet.

### Bewusst nicht Teil dieses Dienstes

| Nicht enthalten | Grund |
|---|---|
| Chat-Verlauf lesen | Lesen ist Sache des `chat-service` (Umsetzungsreihenfolge Schritt 4, zweiter Teil). Der `batch-writer` hat keine HTTP-Schnittstelle |
| Räume, Mitgliedschaften, Tabelle `room` | Nicht Teil dieser Aufgabe. Siehe Abschnitt 4.2 zum fehlenden Fremdschlüssel |
| Keycloak, `web-gateway`, `load-generator` | Nicht Teil dieser Aufgabe |
| `chat.dlq` auslesen oder erneut verarbeiten | Der `batch-writer` legt nur ab. Was in der Dead-Letter-Queue liegt, schaut sich ein Mensch an |
| Alte Nachrichten löschen, Partitionierung | Offener Punkt 2 in `PLANUNG.md`, nicht Teil dieser Aufgabe |

---

## 2. Vertrag: was auf der Queue ankommt

Der Vertrag ist das **JSON** in der Queue, nicht eine Java-Klasse. Der `batch-writer` hat seine
eigene Kopie der Datenklasse (wie in `ChatMessage.java` des `chat-service` vorgesehen).

### 2.1 Woher der Vertrag stammt

Belegt am Code des `chat-service` in diesem Repository:

| Aussage | Beleg |
|---|---|
| Queue-Name `chat.persist`, Dead-Letter-Queue `chat.dlq` | `chat-service/.../config/QueueNames.java` |
| `chat.persist` ist `durable` und leitet Abgelehntes über den Standard-Exchange `""` mit Routing-Key `chat.dlq` weiter | `chat-service/.../config/RabbitConfig.java`, Zeilen 28–30 |
| `chat.dlq` ist `durable`, ohne weitere Argumente | `RabbitConfig.java`, Zeile 37 |
| Gesendet wird über den Standard-Exchange direkt in `chat.persist` | `chat-service/.../service/MessagePublisher.java`, Zeile 35: `convertAndSend(QueueNames.PERSIST_QUEUE, message)` |
| Der Body ist JSON, erzeugt vom `ObjectMapper` von Spring Boot | `RabbitConfig.jsonMessageConverter()` |
| Die sechs Felder und ihre Typen | `chat-service/.../dto/ChatMessage.java` |
| `id` und `sentAt` vergibt der Server, nie der Client | `chat-service/.../service/MessageService.java`, Methode `accept` |
| `roomId` ist nie leer, `senderId`, `senderName`, `content` nie leer oder nur Leerzeichen | `chat-service/.../dto/SendMessageRequest.java` (`@NotNull`, `@NotBlank`) |

Am laufenden Stack überprüfen lässt sich das mit Kriterium A0 in Abschnitt 5: Mit gestopptem
`batch-writer` eine Nachricht senden und sie über die Management-API in `chat.persist` ansehen, ohne
sie zu entfernen.

### 2.2 Die Nachricht

```json
{
  "id":         "0b8f6a52-3c1e-4c55-9d0a-7f3e2c1b9a44",
  "roomId":     "5e2a9c10-1111-4d7e-8a3b-2c4d6e8f0a1b",
  "senderId":   "f3a1c2d4-keycloak-sub",
  "senderName": "Anna Muster",
  "content":    "Hallo zusammen",
  "sentAt":     "2026-09-28T13:39:00.123456789Z"
}
```

| Feld | JSON-Typ | Bedeutung | Pflicht |
|---|---|---|---|
| `id` | String, UUID | Vom `chat-service` vergeben, weltweit eindeutig | ja |
| `roomId` | String, UUID | Raum der Nachricht | ja |
| `senderId` | String | `sub` des Absenders aus Keycloak | ja |
| `senderName` | String | Anzeigename, bewusst mitgespeichert | ja |
| `content` | String | Text der Nachricht | ja |
| `sentAt` | String, ISO-8601 in UTC | Vom `chat-service` gesetzt, Zeitpunkt des **Annehmens** | ja |

### 2.3 Kopfeinträge (Header)

Der `chat-service` setzt `content_type: application/json` und zusätzlich `__TypeId__` mit dem
Klassennamen **seiner** Klasse (`ch.benedict.m321.chatservice.dto.ChatMessage`).

**Regel:** Der `batch-writer` wertet **keinen** Kopfeintrag aus. Er liest den Body immer als
UTF-8-JSON in seine eigene Klasse.

**Begründung:**

- Die Klasse aus `__TypeId__` gibt es im `batch-writer` nicht. Würde er dem Eintrag folgen,
  könnte er keine einzige Nachricht lesen.
- Nachrichten können auch ohne `__TypeId__` ankommen. Szenario S5 legt Nachrichten direkt in die
  Queue, nur mit `content_type`. Diese müssen genauso verarbeitet werden.

Unbekannte zusätzliche Felder im JSON werden ignoriert. So kann der `chat-service` später Felder
ergänzen, ohne den `batch-writer` zu brechen.

### 2.4 Wann eine Nachricht ungültig ist

Eine Nachricht ist **ungültig**, wenn mindestens eines davon zutrifft:

- Der Body ist kein gültiges JSON.
- Eines der sechs Felder fehlt oder ist `null`.
- `id` oder `roomId` ist keine UUID, oder `sentAt` ist kein ISO-8601-Zeitpunkt.

Was mit ungültigen Nachrichten passiert, steht in Abschnitt 3.6.

---

## 3. Verhalten

### 3.1 Normalfall

1. Der `batch-writer` hängt als Verbraucher an `chat.persist`, mit **prefetch 500**: RabbitMQ gibt
   ihm höchstens 500 unbestätigte Nachrichten gleichzeitig.
2. Er sammelt Nachrichten zu einem **Stapel**. Ein Stapel ist fertig, sobald **500 Nachrichten**
   beisammen sind oder **200 ms lang keine neue** Nachricht kam.
3. Jede Nachricht des Stapels wird gelesen und geprüft (2.4). Ungültige gehen nach 3.6.
4. Alle gültigen Nachrichten werden mit **einer einzigen INSERT-Anweisung** eingefügt, die alle
   Zeilen des Stapels enthält: `INSERT ... VALUES (...), (...), ... ON CONFLICT (id) DO NOTHING`.
   Eine einzelne Anweisung ist in PostgreSQL immer genau eine Transaktion: Danach stehen entweder
   alle Zeilen des Stapels in der Tabelle oder keine.
5. **Erst nach dem COMMIT** bestätigt der `batch-writer` jede gültige Nachricht des Stapels (ACK).
   Die Bestätigung macht er selbst (manuelle Bestätigung), nicht automatisch durch Spring.
6. Er protokolliert pro Stapel: Anzahl Nachrichten, davon neu eingefügt, davon als Duplikat
   verworfen, davon ungültig. Die Zahl der neu eingefügten Zeilen meldet die Datenbank als
   Ergebnis der INSERT-Anweisung zurück. Die Differenz zur Zahl der gültigen Nachrichten sind die
   Duplikate.

**Begründung für «erst COMMIT, dann ACK»:** Wäre die Reihenfolge umgekehrt und der Dienst stürzt
dazwischen ab, hätte RabbitMQ die Nachricht schon gelöscht, aber in der Datenbank stünde sie nie.
Andersherum ist der schlimmste Fall ein Duplikat, und das fängt 4.1 ab.

**Begründung für prefetch = Stapelgrösse:** Mit einem kleineren prefetch käme nie ein voller Stapel
zusammen, der Dienst liefe dauernd ins Zeitlimit.

**Hinweis zum Zeitlimit:** Die 200 ms zählen ab der **letzten** Nachricht, nicht ab der ersten.
Kommt ununterbrochen alle paar Millisekunden eine Nachricht, wartet der Stapel, bis 500 beisammen
sind. `PLANUNG.md` 3.6 nennt «500 Stück oder 200 ms». Das hier ist die genaue Bedeutung davon.
Für die Szenarien reicht das. Siehe offener Punkt 1 in Abschnitt 6.

### 3.2 Dieselbe Nachricht kommt zweimal (S5)

Kommt eine Nachricht mit einer `id` an, die schon in der Tabelle steht, oder zweimal im selben
Stapel, dann fügt die Datenbank sie **genau einmal** ein. `ON CONFLICT (id) DO NOTHING` verwirft
die zweite Zeile ohne Fehler. Beide Exemplare werden bestätigt. Nichts landet in `chat.dlq`.

**Begründung:** Ein Duplikat ist bei At-least-once normal und kein Fehler. Es entsteht, wenn
RabbitMQ eine Nachricht erneut zustellt, weil die Bestätigung nicht ankam. Das geht nur, weil die
`id` vom `chat-service` stammt. Eine von der Datenbank vergebene ID wäre bei jedem Versuch neu,
und die Nachricht stünde doppelt im Chat.

### 3.3 batch-writer war gestoppt (S4)

Solange der `batch-writer` nicht läuft, sammeln sich die Nachrichten in `chat.persist`. Die Queue
ist `durable`, und der `chat-service` sendet persistent, deshalb überleben sie auch einen Neustart
von RabbitMQ.

Beim Start bekommt der `batch-writer` sofort bis zu 500 Nachrichten. Er bildet volle Stapel und
schreibt 1000 wartende Nachrichten in **2 Transaktionen**. Dazu kommen beim Start die zwei
Schema-Anweisungen aus 4.3. Die Obergrenze von 100 Transaktionen wird deutlich unterschritten.

### 3.4 Zwei Instanzen gleichzeitig (S6)

Mehrere Instanzen hängen **an derselben Queue** `chat.persist`. RabbitMQ gibt jede Nachricht genau
einer von ihnen (Competing Consumers, `PLANUNG.md` 3.5). Die Instanzen müssen sich dafür nicht
absprechen.

Damit das funktioniert:

- Der Dienst in `docker-compose.yml` hat **keinen** `container_name` und **keinen**
  `ports:`-Eintrag, sonst schlägt `--scale` fehl.
- Das Anlegen des Schemas beim Start ist wiederholbar (`IF NOT EXISTS`, 4.3). Die zweite Instanz
  findet die Tabelle vor und tut nichts.
- Kommt eine Nachricht nach einer erneuten Zustellung bei beiden Instanzen an, fängt sie 3.2 ab.

### 3.5 Die Datenbank ist weg (S7)

**Erkennen:** Als *vorübergehend* gilt jeder Fehler, bei dem keine Verbindung zur Datenbank
zustande kommt oder eine bestehende abbricht. Technisch heisst das: In der Kette der Ursachen
steckt eine `SQLTransientConnectionException` oder eine `SQLException`, deren SQLState mit `08`
beginnt (Klasse «connection exception»). Das gilt auch, wenn Spring sie in eine eigene Ausnahme
verpackt hat, etwa `CannotGetJdbcConnectionException`.

**Verhalten:**

1. Der Stapel wird **weder bestätigt noch abgelehnt**. Er bleibt beim `batch-writer`.
2. Der `batch-writer` wartet und versucht **denselben Stapel** erneut. Die Pausen wachsen: 1 s,
   2 s, 4 s, 8 s, danach immer 10 s.
3. Er versucht es **ohne Obergrenze**, bis der COMMIT gelingt. Dann bestätigt er wie im Normalfall.
4. Weitere Nachrichten bleiben so lange in `chat.persist`. Keine Nachricht geht wegen eines
   Ausfalls nach `chat.dlq`.
5. Der Prozess läuft weiter, er beendet sich nicht. Jeder Fehlversuch wird mit Pause und
   Versuchsnummer protokolliert.
6. Ein Verbindungsversuch gibt nach **5 s** auf (`connectionTimeout` des Verbindungspools). So
   meldet sich ein Ausfall schnell, statt 30 s zu blockieren. Der Pool baut die Verbindungen
   selbst neu auf, sobald Postgres wieder da ist.
7. Wird der `batch-writer` während des Wartens beendet, bricht das Warten ab. RabbitMQ stellt die
   unbestätigten Nachrichten erneut zu, an eine andere Instanz oder beim nächsten Start.

**Zeitrechnung für S7:** Solange Postgres weg ist, dauert jeder Versuch bis zu 5 s
(`connectionTimeout`), danach folgt die Pause. Postgres ist nach rund 18 s wieder bereit (15 s
Ausfall plus Startzeit). Spätestens der Versuch, der danach beginnt, gelingt. Die 300 Nachrichten
stehen nach höchstens rund 40 s in der Tabelle, weit unter 90 s.

**Begründung:** Ein Ausfall ist vorübergehend, die Nachrichten selbst sind in Ordnung. Würde man
sie nach wenigen Fehlversuchen in `chat.dlq` schieben, lägen nach 15 s Ausfall alle im
Abstellgleis, und jemand müsste sie von Hand zurückholen. Nicht bestätigen ist hier die sichere
Wahl: Stirbt der Dienst, bekommt ein anderer die Nachrichten.

**Verworfen:** Wiederholen mit fester Obergrenze, etwa 3 Versuche und dann `chat.dlq`. Das würde
Ausfall und kaputte Nachricht gleich behandeln, obwohl sie das Gegenteil brauchen.

### 3.6 Eine Nachricht ist ungültig

**Verhalten:** Eine ungültige Nachricht (2.4) wird **sofort** ohne Wiedereinreihung abgelehnt
(`reject`, `requeue = false`). RabbitMQ leitet sie über die Dead-Letter-Einstellung von
`chat.persist` nach `chat.dlq` weiter und vermerkt den Grund im Kopfeintrag `x-death`. Der Rest des
Stapels wird normal geschrieben. Der `batch-writer` protokolliert eine Warnung mit dem Grund.

**Begründung:** Kaputtes JSON wird beim zweiten und dritten Versuch nicht besser, jeder Versuch
hat dasselbe Ergebnis. Eine kaputte Nachricht darf die 499 anderen ihres Stapels nicht aufhalten.

**Abweichung von `PLANUNG.md` 3.5:** Dort steht «Dead Letter, nach 3 fehlgeschlagenen Versuchen».
Wiederholen hilft aber nur bei vorübergehenden Fehlern, und die sind in 3.5 geregelt. Bei einer
ungültigen Nachricht bringen drei Versuche nur Wartezeit. Deshalb geht sie beim ersten Mal nach
`chat.dlq`.

### 3.7 Die Datenbank lehnt eine gültige Nachricht ab

Das ist ein Fehler, der **nicht** in 3.5 fällt, zum Beispiel ein Wert, den die Spalte nicht
aufnehmen kann. Mit der Prüfung aus 2.4 und dem Schema aus 4.1 sollte das nicht vorkommen. Es ist
trotzdem geregelt, weil ein einziger solcher Fall sonst den ganzen Stapel blockiert:

1. Die INSERT-Anweisung des Stapels schlägt als Ganzes fehl. Keine ihrer Zeilen ist gespeichert.
2. Der `batch-writer` schreibt die Nachrichten dieses Stapels **einmal einzeln**, jede in einer
   eigenen Transaktion.
3. Jede, die dabei gelingt, wird bestätigt. Jede, die weiterhin abgelehnt wird, geht wie in 3.6
   nach `chat.dlq`.

**Begründung:** Bei einem Bulk-INSERT reisst eine schlechte Zeile alle anderen mit. Der Einzelweg
findet heraus, welche es war, und rettet die anderen.

### 3.8 RabbitMQ ist weg oder der Dienst stürzt ab

- **RabbitMQ weg:** Spring AMQP baut die Verbindung selbst neu auf. Was zum Zeitpunkt des Abbruchs
  unbestätigt war, stellt RabbitMQ erneut zu. Mögliche Duplikate fängt 3.2 ab.
- **Absturz nach dem COMMIT, vor dem ACK:** Die Nachrichten kommen erneut. 3.2 verwirft sie.
- **Absturz vor dem COMMIT:** Die Transaktion ist nie committet. Die Nachrichten kommen erneut und
  werden normal geschrieben.

### 3.9 Start, bevor der chat-service lief

Der `batch-writer` legt beim Start `chat.persist` und `chat.dlq` selbst an, und zwar **mit genau
denselben Eigenschaften** wie `RabbitConfig.java` im `chat-service`: beide `durable`, `chat.persist`
mit Dead-Letter-Exchange `""` und Dead-Letter-Routing-Key `chat.dlq`. Existieren sie schon,
passiert nichts.

**Begründung:** So ist die Startreihenfolge egal, und die Tests brauchen keinen `chat-service`.
Weichen die Eigenschaften ab, verweigert RabbitMQ das Anlegen mit `PRECONDITION_FAILED`. Deshalb
müssen beide Stellen gleich bleiben. Das ist ein Punkt, an dem die zwei Dienste bewusst gekoppelt
sind.

---

## 4. Datenmodell und Konfiguration

### 4.1 Tabelle `message`

Spalten nach `PLANUNG.md` 3.7:

```sql
CREATE TABLE IF NOT EXISTS message (
    id          UUID        PRIMARY KEY,
    room_id     UUID        NOT NULL,
    sender_id   VARCHAR     NOT NULL,
    sender_name VARCHAR     NOT NULL,
    content     TEXT        NOT NULL,
    sent_at     TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_message_room_sent_at
    ON message (room_id, sent_at DESC);
```

| Entscheid | Begründung |
|---|---|
| `id` ist Primärschlüssel und kommt vom `chat-service` | Nur so kann eine erneut zugestellte Nachricht mit `ON CONFLICT (id) DO NOTHING` erkannt werden (3.2) |
| Kein `DEFAULT` auf `id` und `sent_at` | Beide vergibt der `chat-service`. Ein `DEFAULT now()` hielte den Zeitpunkt des **Schreibens** fest, und alle 500 Nachrichten eines Stapels hätten dieselbe Zeit |
| `TIMESTAMPTZ` statt `TIMESTAMP` | `sentAt` ist ein Zeitpunkt in UTC. Ohne Zeitzone ginge diese Information beim Speichern verloren |
| `VARCHAR` ohne Längenangabe | So steht es in 3.7. Auch der `chat-service` begrenzt die Länge nicht. Eine willkürliche Grenze würde gültige Nachrichten ablehnen (3.7) |
| Alle Spalten `NOT NULL` | Der `chat-service` liefert immer alle Felder (2.1), und der `batch-writer` prüft sie vorher (2.4) |
| Index `(room_id, sent_at DESC)` | Vorgabe aus 3.7 für die spätere Abfrage «die letzten 50 Nachrichten eines Raums» |

### 4.2 Kein Fremdschlüssel auf `room`

`PLANUNG.md` 3.7 zeigt `room_id` als Fremdschlüssel auf die Tabelle `room`. Diesen Fremdschlüssel
gibt es in diesem Schritt **nicht**, und die Tabelle `room` auch nicht.

**Begründung:** Die Aufgabe verlangt die **Spalten** aus 3.7 und schliesst Räume ausdrücklich aus.
Ohne Raumverwaltung ist `room` leer. Ein Fremdschlüssel würde deshalb **jede** Nachricht ablehnen.
Er kommt dazu, wenn die Raumverwaltung gebaut wird.

### 4.3 Wo das Schema entsteht

Der `batch-writer` legt das Schema **selbst beim Start** an: Spring Boot führt
`batch-writer/src/main/resources/schema.sql` aus (`spring.sql.init.mode: always`). Die Datei
enthält genau die zwei Anweisungen aus 4.1.

| | Begründung |
|---|---|
| **Gewählt:** beim Start des `batch-writer` | Er ist der einzige Schreiber, also gehört ihm die Tabelle. Die Tests mit Testcontainers bekommen das Schema ohne Zusatzaufwand. Dank `IF NOT EXISTS` ist es wiederholbar, bei jedem Start und bei zwei Instanzen |
| Verworfen: Init-Skript im Postgres-Container | Läuft nur beim allerersten Start auf einem leeren Volume. Die Tests bräuchten es zusätzlich separat, dann gäbe es zwei Stellen für dasselbe Schema |
| Verworfen: Flyway | Ein weiteres Werkzeug mit eigener Versionstabelle. Für eine Tabelle zu viel. Kommt, sobald sich das Schema ändern muss |

### 4.4 Stapel und Wiederholung

Diese Werte sind feste Entscheide des Dienstes und stehen in `application.yml`, nicht in `.env`:

| Einstellung | Wert | Wo begründet |
|---|---|---|
| prefetch | 500 | 3.1 |
| Stapelgrösse | 500 | `PLANUNG.md` 3.6, 4.1 |
| Zeitlimit ohne neue Nachricht | 200 ms | 3.1 |
| Pausen bei Datenbankausfall | 1, 2, 4, 8, dann 10 s | 3.5 |
| `connectionTimeout` des Pools | 5 s | 3.5 |

**Eine Anweisung pro Stapel.** Der `batch-writer` baut die INSERT-Anweisung selbst, mit so vielen
Zeilen `(?, ?, ?, ?, ?, ?)` wie der Stapel gültige Nachrichten hat. Die Werte gehen als Parameter
hinein, nie in den SQL-Text. Bei 500 Nachrichten sind das 3000 Parameter, weit unter der Grenze
von 32'767 Parametern pro Anweisung.

| | Begründung |
|---|---|
| **Gewählt:** eine INSERT-Anweisung mit allen Zeilen | Genau «ein Bulk-INSERT» wie in `PLANUNG.md` 3.6. Ein einziger Weg zur Datenbank, eine Transaktion, und die Datenbank meldet zurück, wie viele Zeilen neu waren |
| Verworfen: JDBC-Batch mit `reWriteBatchedInserts=true` | Der Treiber fasst die Zeilen zwar auch zusammen, meldet dann aber nicht mehr, wie viele neu eingefügt wurden. Damit liessen sich Duplikate nicht zählen (3.1, Schritt 6) |

### 4.5 Umgebungsvariablen

Zugangsdaten kommen **nur** aus `.env`. Im Repository steht nur `.env.example` (`CLAUDE.md`,
«Keine Geheimnisse im Repository»).

**Neu in `.env.example`:**

| Variable | Beispielwert | Wer liest sie |
|---|---|---|
| `POSTGRES_DB` | `chat` | Container `postgres` legt die Datenbank an, `batch-writer` verbindet sich |
| `POSTGRES_USER` | `chat` | beide |
| `POSTGRES_PASSWORD` | `bitte-lokal-aendern` | beide |

**Bestehend, jetzt auch vom `batch-writer` gelesen:** `RABBITMQ_USER`, `RABBITMQ_PASSWORD`.

**Nur in `docker-compose.yml` gesetzt, nicht in `.env`**, weil sie im Docker-Netz fest sind:

| Variable | Wert im Stack | Vorgabe ausserhalb von Docker |
|---|---|---|
| `RABBITMQ_HOST` | `rabbitmq` | `localhost` |
| `POSTGRES_HOST` | `postgres` | `localhost` |

### 4.6 Neue Dienste in `docker-compose.yml`

**`postgres`:** Image `postgres:17`, Zugangsdaten aus `.env`, Daten in einem benannten Volume (so
überleben sie ein `stop`/`start`, S7), Healthcheck mit `pg_isready`, Netz `chat-net`, **kein**
`ports:`.

**`batch-writer`:** gebaut aus `batch-writer/Dockerfile` (mehrstufig wie beim `chat-service`),
Netz `chat-net`, **kein** `ports:` und **kein** `container_name` (3.4). Er startet erst, wenn
`rabbitmq` und `postgres` gesund sind (`depends_on` mit `condition: service_healthy`).
`restart: unless-stopped`: Stürzt der Dienst trotz 3.5 ab, startet Docker ihn neu. Ein
absichtliches `docker compose stop` (S4) bleibt gestoppt.

### 4.7 Maven

`batch-writer` ist ein Modul im Eltern-POM (`<module>batch-writer</module>`), wie der
`chat-service`. Paket `ch.benedict.m321.batchwriter`. Abhängigkeiten: `spring-boot-starter-amqp`,
`spring-boot-starter-jdbc`, `spring-boot-starter-json` (liefert den `ObjectMapper`, der das JSON
liest), `postgresql`, Lombok (für `@Slf4j` und `@RequiredArgsConstructor`, wie
in `CLAUDE.md` vorgesehen). Für die Tests Testcontainers mit `rabbitmq` und `postgresql`. Kein
Webserver: Der Dienst hat keine HTTP-Schnittstelle.

---

## 5. Abnahmekriterien

Alle Befehle laufen im Wurzelverzeichnis des Repositorys, in PowerShell oder bash. Alles, was
verschachtelte Anführungszeichen braucht, steht in Skripten unter `scripts/` und läuft **im
Docker-Netz**. In der Windows-Eingabeaufforderung (cmd) steht im `docker run`-Befehl `%cd%` statt
`$PWD`. Die Szenarien bauen aufeinander auf, ohne Aufräumen dazwischen. Deshalb
bekommt jedes Szenario seinen eigenen Text-Präfix (`S3-`, `S4-`, ...) und wird nur daran gezählt.

Die Hilfsskripte (entstehen laut Plan):

| Skript | Tut |
|---|---|
| `scripts/send.sh <präfix> <anzahl>` | schickt `<anzahl>` Nachrichten per `POST /messages` an den `chat-service`, Text `<präfix>-1`, `<präfix>-2`, ... |
| `scripts/publish-twice.sh` | legt dieselbe Nachricht zweimal direkt in `chat.persist`, nur mit `content_type: application/json` |
| `scripts/peek-persist.sh` | zeigt die erste Nachricht in `chat.persist`, ohne sie zu entfernen |

Aufgerufen werden sie in einem kurzlebigen Container im Netz `chat-net`:

```
docker run --rm --network chat-net --env-file .env -v "$PWD/scripts:/scripts:ro" curlimages/curl sh /scripts/<skript> <argumente>
```

Im Folgenden steht dafür kurz **`RUN <skript> <argumente>`**.

Messbefehle, im Folgenden kurz **`SQL "<abfrage>"`** und **`QUEUES`**:

```
docker compose exec postgres psql -U chat -d chat -tAc "<abfrage>"
docker compose exec rabbitmq rabbitmqctl list_queues name messages consumers
```

| Nr | Kriterium | Befehl | Bestanden, wenn |
|---|---|---|---|
| A0 | Vertrag stimmt mit 2.2 überein | `docker compose stop batch-writer`, `RUN send.sh A0 1`, `RUN peek-persist.sh` | Payload hat genau die sechs Felder aus 2.2, `sentAt` im ISO-Format |
| A1 | Tests grün (S1) | `mvn clean test` | `BUILD SUCCESS`, 0 Failures, 0 Errors |
| A2 | Stack läuft ohne offenen Port (S2) | frischer Klon, `cp .env.example .env`, `docker compose up -d --build`, dann `docker compose ps` | alle vier Dienste `running`, Postgres und RabbitMQ `healthy`, in der Spalte PORTS kein `->` |
| A3 | Durchsatz (S3) | `RUN send.sh S3 1000`, bis 60 s warten, dann `SQL "SELECT count(*) FROM message WHERE content LIKE 'S3-%'"` und `QUEUES` | `1000`, `chat.persist` hat 0 messages |
| A4 | Nichts verloren nach Stopp, gebündelt (S4) | `docker compose stop batch-writer`, `RUN send.sh S4 1000`, `SQL "SELECT xact_commit FROM pg_stat_database WHERE datname = 'chat'"` merken, `docker compose start batch-writer`, warten bis `chat.persist` leer, `xact_commit` erneut lesen, dann `SQL "SELECT count(*) FROM message WHERE content LIKE 'S4-%'"` | `1000`, Differenz von `xact_commit` höchstens 100 |
| A5 | Duplikat (S5) | `RUN publish-twice.sh`, 5 s warten, `SQL "SELECT count(*) FROM message WHERE content = 'S5-duplikat'"` und `QUEUES` | `1`, `chat.dlq` hat 0 messages |
| A6 | Zwei Instanzen (S6) | `docker compose up -d --scale batch-writer=2`, `QUEUES`, `RUN send.sh S6 1000`, bis 60 s warten, `SQL "SELECT count(*) FROM message WHERE content LIKE 'S6-%'"`, `docker compose logs batch-writer` | `chat.persist` hat 2 consumers. Genau `1000`. Im Log schreiben beide Instanzen Stapel |
| A7 | Datenbankausfall (S7) | `docker compose stop postgres`, `RUN send.sh S7 300`, 15 s warten, `docker compose start postgres`, bis 90 s warten, `SQL "SELECT count(*) FROM message WHERE content LIKE 'S7-%'"`, `QUEUES`, `docker compose ps batch-writer` | `300`, `chat.dlq` hat 0 messages. Alle `batch-writer` sind `running`, und ihre Laufzeit in der Spalte STATUS ist länger als S7 gedauert hat (also kein Neustart) |
| A8 | Code-Regeln (S8) | `git grep -n "stream()" -- batch-writer/` und `git ls-files .env` | keine Treffer, keine Ausgabe. Dazu von Hand: Über jeder Klasse und jeder Methode in `batch-writer/` steht ein Kommentar |
| A9 | Pflicht-Tests vorhanden | `mvn -pl batch-writer test` | Ein Test legt dieselbe Nachricht zweimal in die Queue und findet genau eine Zeile (3.2). Ein Test hält Postgres an, schickt Nachrichten, lässt Postgres wieder laufen und findet alle in der Tabelle (3.5) |

In A3 bis A7 verwenden die Befehle die Beispielwerte aus `.env.example` (`chat`/`chat`). Wer andere
Werte in `.env` hat, passt `-U` und `-d` an.

---

## 6. Offene Punkte

| # | Punkt | Stand |
|---|---|---|
| 1 | **Keine feste Obergrenze der Wartezeit pro Stapel.** Das Zeitlimit zählt ab der letzten Nachricht (3.1). Bei gleichmässigem, langsamem Strom kann eine Nachricht warten, bis 500 beisammen sind | Für die Szenarien ohne Folgen. Lösung wäre ein eigener Puffer mit Zeitgeber, bewusst zurückgestellt |
| 2 | **Zwei Instanzen starten gleichzeitig auf leerer Datenbank.** Zwei gleichzeitige `CREATE TABLE IF NOT EXISTS` können sich in PostgreSQL gegenseitig stören | In den Szenarien existiert die Tabelle beim Skalieren schon. Falls es passiert: `restart: unless-stopped` startet den Dienst neu, beim zweiten Mal ist die Tabelle da |
| 3 | **Sehr langer Datenbankausfall.** RabbitMQ schliesst den Kanal, wenn eine Nachricht länger als 30 min unbestätigt bleibt (`consumer_timeout`) | Die Nachrichten kommen dann erneut, es geht nichts verloren. Relevant erst bei Ausfällen über 30 min |
| 4 | **Fremdschlüssel auf `room`** | Kommt mit der Raumverwaltung (4.2) |
