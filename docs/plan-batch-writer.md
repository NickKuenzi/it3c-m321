# batch-writer — Umsetzungsplan

**Ziel:** Der `batch-writer` holt Nachrichten aus `chat.persist` und legt sie gebündelt und
dauerhaft in der Tabelle `message` ab. Duplikate, ungültige Nachrichten und ein Ausfall der
Datenbank sind so behandelt, wie es die Spezifikation verlangt.

**Spezifikation:** [`spec-batch-writer.md`](spec-batch-writer.md). Jeder Schritt unten nennt den
Abschnitt, den er umsetzt.

**Architektur:** Ein Spring-Boot-Dienst ohne Webserver. `config` richtet beim Start die Queues und
den Stapel-Empfang ein. `message` enthält den Weg einer Nachricht: `MessageParser` liest das JSON,
`MessageWriter` schreibt in die Datenbank, `BatchConsumer` hält beides zusammen und bestätigt.

**Tech-Stack:** Java 21, Spring Boot 3.5.16 (über das Eltern-POM), Spring AMQP, Spring JDBC
(`JdbcTemplate`), PostgreSQL 17, RabbitMQ 3.13, Lombok, JUnit 5, Testcontainers.

---

## Globale Vorgaben

Diese Punkte gelten für **jede** Aufgabe:

- **Regeln aus `CLAUDE.md`:** Code auf Englisch (auch Log-Meldungen), Kommentare und
  Commit-Messages auf Deutsch. Ein Ergebnis pro Zeile, in eine benannte Variable. `for`-Schleife
  statt Stream. Lombok nur für `@Slf4j` und `@RequiredArgsConstructor`, Datenklassen als `record`.
- **Über jeder Klasse und jeder Methode steht ein Kommentar**, der erklärt, *warum* es sie gibt.
  Das gilt auch für Testklassen und Testmethoden (Szenario S8).
- **Kein `ports:`- und kein `container_name`-Eintrag** in `docker-compose.yml` (S2, S6).
- **Keine Geheimnisse im Repository.** Zugangsdaten nur in `.env`, im Repo nur `.env.example`.
- **Tests mit echter Queue und echter Datenbank:** Integrationstests starten RabbitMQ und
  PostgreSQL über Testcontainers. Docker Desktop muss laufen.
- **Ein Task, ein Commit.** Erst committen, wenn der Test des Tasks grün ist:
  ```
  mvn -pl batch-writer -am test
  git add <Dateien des Tasks>
  git commit -m "<Commit-Message des Tasks>" --trailer "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
  git push
  ```

---

## Abgrenzung

| Bewusst **nicht** in diesem Plan | Warum |
|---|---|
| Chat-Verlauf lesen, Räume, Keycloak, `web-gateway`, `load-generator` | Nicht Teil der Aufgabe (Spezifikation 1) |
| `chat.dlq` auslesen oder erneut verarbeiten | Der `batch-writer` legt nur ab (Spezifikation 1) |
| Quorum-Queue, `x-delivery-limit` | `chat.persist` ist eine klassische Queue und wird vom `chat-service` so angelegt. Der `batch-writer` darf sie nicht anders anlegen (Spezifikation 3.9) |
| Flyway | Eine Tabelle, ein Skript mit `IF NOT EXISTS` (Spezifikation 4.3) |

---

## Dateistruktur

```
pom.xml                                   # Modul batch-writer eintragen
docker-compose.yml                        # + postgres, + batch-writer
.env.example                              # + POSTGRES_DB, POSTGRES_USER, POSTGRES_PASSWORD
.gitattributes                            # Skripte immer mit LF-Zeilenenden
chat-service/Dockerfile                   # kennt das neue Modul (Task 1)
scripts/
├── send.sh                               # Nachrichten per POST /messages
├── publish-twice.sh                      # dieselbe Nachricht zweimal direkt in chat.persist
└── peek-persist.sh                       # erste Nachricht in chat.persist ansehen
batch-writer/
├── pom.xml
├── Dockerfile
└── src/
    ├── main/
    │   ├── java/ch/benedict/m321/batchwriter/
    │   │   ├── BatchWriterApplication.java
    │   │   ├── config/
    │   │   │   ├── QueueNames.java              # die Namen an genau einer Stelle
    │   │   │   └── RabbitConfig.java            # Queues wie im chat-service, Stapel-Empfang
    │   │   ├── dto/
    │   │   │   └── ChatMessage.java             # eigene Kopie des Vertrags
    │   │   └── message/
    │   │       ├── InvalidMessageException.java
    │   │       ├── MessageParser.java           # JSON lesen und prüfen
    │   │       ├── MessageWriter.java           # ein INSERT pro Stapel
    │   │       ├── DatabaseErrors.java          # Ausfall erkennen
    │   │       └── BatchConsumer.java           # Stapel empfangen, schreiben, bestätigen
    │   └── resources/
    │       ├── application.yml
    │       └── schema.sql
    └── test/java/ch/benedict/m321/batchwriter/
        ├── BatchWriterApplicationTest.java
        ├── config/RabbitConfigIntegrationTest.java
        └── message/
            ├── MessageParserTest.java
            ├── MessageWriterIntegrationTest.java
            ├── BatchConsumerIntegrationTest.java
            ├── DatabaseErrorsTest.java
            └── DatabaseOutageIntegrationTest.java
```

---

## Reihenfolge

| Task | Inhalt | Warum an dieser Stelle |
|---|---|---|
| 1 | Maven-Modul und Start | Ohne ein Modul, das der Eltern-Build kennt, lässt sich nichts bauen und nichts testen (S1) |
| 2 | Tabelle `message` | Der Schreiber braucht die Tabelle, und sie lässt sich ohne Queue prüfen |
| 3 | Nachricht lesen und prüfen | Der Vertrag steht vor allem, was ihn benutzt. Reiner Unit-Test, schnell |
| 4 | Stapel schreiben | Die Datenbankhälfte des Wegs, prüfbar ohne RabbitMQ |
| 5 | Queues und Stapel-Empfang | Die Queue muss genau so existieren wie im `chat-service`, bevor ein Verbraucher daran hängt |
| 6 | Verbraucher: empfangen, schreiben, bestätigen | Erst jetzt gibt es beide Hälften, die er verbindet. Normalfall und Duplikat (S3, S5) |
| 7 | Datenbankausfall: warten und wiederholen | Setzt auf den funktionierenden Normalfall auf und ändert nur, was bei einem Fehler passiert (S7) |
| 8 | Einzelweg bei abgelehnter Zeile | Nutzt das Warten aus Task 7 für jede einzelne Nachricht |
| 9 | Docker und Compose | Erst muss der Dienst als Code stimmen. Der Container ist nur die Verpackung (S2, S4, S6) |
| 10 | Szenario-Skripte | Braucht den laufenden Stack aus Task 9 |
| 11 | README «Stand» | Zum Schluss, wenn feststeht, was wirklich läuft |

---

## Task 1: Maven-Modul und Start

**Setzt um:** Spezifikation 4.7

**Dateien:**
- Ändern: `pom.xml` — `<module>batch-writer</module>` eintragen
- Ändern: `chat-service/Dockerfile` — zusätzlich `COPY batch-writer/pom.xml batch-writer/pom.xml`
- Erstellen: `batch-writer/pom.xml`
- Erstellen: `batch-writer/src/main/java/ch/benedict/m321/batchwriter/BatchWriterApplication.java`
- Erstellen: `batch-writer/src/main/resources/application.yml`
- Test: `batch-writer/src/test/java/ch/benedict/m321/batchwriter/BatchWriterApplicationTest.java`

**Was entsteht:**
- `batch-writer/pom.xml` mit dem Eltern-POM, `spring-boot-starter-amqp`, `spring-boot-starter-jdbc`,
  `spring-boot-starter-json`, `postgresql` (runtime), Lombok (optional). Für die Tests
  `spring-boot-starter-test`, `spring-boot-testcontainers`, Testcontainers `junit-jupiter`,
  `rabbitmq` und `postgresql`. Kein Webserver.
- `application.yml` liest Host, Benutzer und Passwort für RabbitMQ und PostgreSQL aus den
  Umgebungsvariablen aus Spezifikation 4.5, mit `localhost` als Vorgabe.

**Warum `chat-service/Dockerfile` mitändern:** Sobald das Eltern-POM zwei Module nennt, will Maven
beim Bauen beide `pom.xml` sehen, auch wenn nur eines gebaut wird. Ohne diese Zeile bricht
`docker compose up --build` für den `chat-service` ab.

**Test:** `BatchWriterApplicationTest` startet RabbitMQ und PostgreSQL per Testcontainers und prüft,
dass die Anwendung hochfährt.

**Prüfen:** `mvn -pl batch-writer -am test` grün, danach `mvn clean test` im Wurzelverzeichnis grün.

**Commit:** `chore: Maven-Modul batch-writer anlegen`

---

## Task 2: Tabelle `message`

**Setzt um:** Spezifikation 4.1, 4.2, 4.3

**Dateien:**
- Erstellen: `batch-writer/src/main/resources/schema.sql`
- Ändern: `batch-writer/src/main/resources/application.yml` — `spring.sql.init.mode: always`
- Ändern: `BatchWriterApplicationTest.java`

**Was entsteht:** `schema.sql` mit genau den zwei Anweisungen aus Spezifikation 4.1, beide mit
`IF NOT EXISTS`. Kein Fremdschlüssel (4.2).

**Test:**
- Nach dem Start hat die Tabelle `message` genau die sechs Spalten mit den Typen aus 4.1
  (abgefragt über `information_schema.columns`).
- Der Index `idx_message_room_sent_at` existiert (`pg_indexes`).
- `schema.sql` ein zweites Mal ausführen wirft keinen Fehler (wiederholbar, S6).

**Commit:** `feat: Tabelle message beim Start anlegen`

---

## Task 3: Nachricht lesen und prüfen

**Setzt um:** Spezifikation 2.2, 2.3, 2.4

**Dateien:**
- Erstellen: `batch-writer/src/main/java/ch/benedict/m321/batchwriter/dto/ChatMessage.java`
- Erstellen: `.../message/InvalidMessageException.java`
- Erstellen: `.../message/MessageParser.java`
- Test: `.../message/MessageParserTest.java`

**Was entsteht:**
- `ChatMessage` als `record` mit den sechs Feldern aus 2.2.
- `MessageParser.parse(byte[] body)` liest den Body mit dem `ObjectMapper` von Spring Boot in ein
  `ChatMessage`. Kopfeinträge sieht er gar nicht, er bekommt nur den Body (2.3). Ist der Body kein
  gültiges JSON, fehlt ein Feld oder ist ein Feld `null`, wirft er `InvalidMessageException` mit
  dem Grund.

**Test** (ohne Spring, der `ObjectMapper` kommt aus `Jackson2ObjectMapperBuilder`, damit er genau
so eingestellt ist wie in der Anwendung):
- JSON genau im Format des `chat-service` wird vollständig gelesen, `sentAt` als ISO-Zeitpunkt.
- Ein unbekanntes zusätzliches Feld wird ignoriert.
- Kein JSON, leerer Body, `null` als Body → `InvalidMessageException`.
- `id` fehlt, `id` ist keine UUID, `sentAt` ist kein Zeitpunkt → `InvalidMessageException`.

**Commit:** `feat: Nachrichten im Format des chat-service lesen und pruefen`

---

## Task 4: Stapel schreiben

**Setzt um:** Spezifikation 3.1 (Schritt 4 und 6), 3.2, 4.4

**Dateien:**
- Erstellen: `.../message/MessageWriter.java`
- Test: `.../message/MessageWriterIntegrationTest.java`

**Was entsteht:** `MessageWriter.writeBatch(List<ChatMessage>)` baut **eine** INSERT-Anweisung mit
so vielen Zeilen wie Nachrichten und gibt zurück, wie viele Zeilen neu eingefügt wurden:

```sql
INSERT INTO message (id, room_id, sender_id, sender_name, content, sent_at)
VALUES (?, ?, ?, ?, ?, ?), (?, ?, ?, ?, ?, ?), ...
ON CONFLICT (id) DO NOTHING
```

Die Zeilen `(?, ...)` entstehen in einer `for`-Schleife, die Werte gehen als Parameter hinein.
`sentAt` wird als `OffsetDateTime` in UTC übergeben, weil der PostgreSQL-Treiber `Instant` nicht
direkt kennt.

**Test** (PostgreSQL per Testcontainers, Tabelle vor jedem Test leeren):
- Drei Nachrichten → Rückgabe 3, drei Zeilen, alle Werte stimmen, auch `sent_at`.
- Dieselben drei noch einmal → Rückgabe 0, weiterhin drei Zeilen (Duplikat, 3.2).
- Dieselbe `id` zweimal im selben Aufruf → Rückgabe 1, eine Zeile.

**Commit:** `feat: Stapel mit einer einzigen INSERT-Anweisung schreiben`

---

## Task 5: Queues und Stapel-Empfang

**Setzt um:** Spezifikation 3.1 (Schritte 1, 2, 5), 3.9, 4.4

**Dateien:**
- Erstellen: `.../config/QueueNames.java`
- Erstellen: `.../config/RabbitConfig.java`
- Ändern: `application.yml` — Block `batch-writer:` mit `prefetch`, `batch-size`, `batch-timeout-ms`
- Test: `.../config/RabbitConfigIntegrationTest.java`

**Was entsteht:**
- `QueueNames` mit `chat.persist` und `chat.dlq`.
- `RabbitConfig` legt `chat.persist` und `chat.dlq` **genau wie `RabbitConfig` im `chat-service`**
  an: beide `durable`, `chat.persist` mit Dead-Letter-Exchange `""` und Routing-Key `chat.dlq`.
- Eine eigene `rabbitListenerContainerFactory` für den Stapel-Empfang:

| Einstellung | Wert | Bedeutung |
|---|---|---|
| `batchListener`, `consumerBatchEnabled` | `true` | Die Listener-Methode bekommt eine Liste |
| `batchSize` | 500 | Ein Stapel ist bei 500 Nachrichten voll |
| `receiveTimeout` | 200 ms | Kommt 200 ms nichts, geht der Stapel los |
| `prefetchCount` | 500 | So viele darf der Verbraucher unbestätigt halten |
| `acknowledgeMode` | `MANUAL` | Der Verbraucher bestätigt selbst, nach dem Schreiben |

**Test:**
- `chat.persist` mit **der Definition des `chat-service`** noch einmal anlegen wirft keinen Fehler.
  Das beweist, dass die Eigenschaften gleich sind. Sonst meldet RabbitMQ `PRECONDITION_FAILED`.
- `chat.dlq` existiert.

**Commit:** `feat: Queues wie im chat-service anlegen und Stapel-Empfang einrichten`

---

## Task 6: Verbraucher — empfangen, schreiben, bestätigen

**Setzt um:** Spezifikation 3.1, 3.2, 3.6

**Dateien:**
- Erstellen: `.../message/BatchConsumer.java`
- Test: `.../message/BatchConsumerIntegrationTest.java`

**Was entsteht:** `BatchConsumer.receiveBatch(List<Message> batch, Channel channel)` mit
`@RabbitListener` auf `chat.persist`:

1. Jede Nachricht mit `MessageParser` lesen. Ungültige sofort mit `basicReject(tag, false)`
   ablehnen. RabbitMQ leitet sie nach `chat.dlq` (3.6).
2. Alle gültigen mit `MessageWriter.writeBatch` schreiben.
3. Danach jede gültige mit `basicAck(tag, false)` bestätigen (erst COMMIT, dann ACK).
4. Eine Log-Zeile pro Stapel: gültig, neu, Duplikate, ungültig.

**Test** (RabbitMQ und PostgreSQL per Testcontainers, Queues und Tabelle vor jedem Test leeren.
Nachrichten werden **roh** gesendet, als JSON-Bytes nur mit `content_type: application/json`,
genau wie in S5):
- Eine Nachricht im Format des `chat-service` ohne `__TypeId__` → eine Zeile mit den richtigen Werten.
- **Duplikat (S5):** dieselbe Nachricht zweimal senden, danach eine Markierungsnachricht. Steht die
  Markierung in der Tabelle, sind die beiden davor sicher verarbeitet. Dann: genau eine Zeile mit
  dieser `id`, `chat.dlq` leer.
- Ungültiges JSON und eine gültige Nachricht → die gültige steht in der Tabelle, `chat.dlq` hat 1.
- 1200 Nachrichten → alle 1200 in der Tabelle, `chat.persist` leer, ein Verbraucher an der Queue.

**Commit:** `feat: Nachrichten aus chat.persist gebuendelt in die Datenbank schreiben`

---

## Task 7: Datenbankausfall — warten und wiederholen

**Setzt um:** Spezifikation 3.5

**Dateien:**
- Erstellen: `.../message/DatabaseErrors.java`
- Ändern: `.../message/BatchConsumer.java` — Schreiben in einer Warteschleife
- Ändern: `application.yml` — `spring.datasource.hikari.connection-timeout: 5000`,
  `batch-writer.retry-pauses-ms: 1000,2000,4000,8000,10000`
- Test: `.../message/DatabaseErrorsTest.java`
- Test: `.../message/DatabaseOutageIntegrationTest.java`

**Was entsteht:**
- `DatabaseErrors.isConnectionProblem(Throwable)` geht die Kette der Ursachen durch. `true`, wenn
  darin eine `SQLTransientConnectionException` steckt oder eine `SQLException` mit SQLState `08...`.
- Im `BatchConsumer`: Schlägt `writeBatch` mit einem Verbindungsproblem fehl, wird nichts bestätigt
  und nichts abgelehnt. Er wartet (1, 2, 4, 8, dann immer 10 s) und versucht denselben Stapel
  erneut, ohne Obergrenze. Jeder Fehlversuch wird mit Versuchsnummer und Pause protokolliert.

**Test:**
- `DatabaseErrorsTest`: SQLState `08001` → Ausfall. SQLState `23505` → kein Ausfall.
  `SQLTransientConnectionException` → Ausfall. In `CannotGetJdbcConnectionException` verpackt →
  Ausfall.
- **Ausfall (S7):** `DatabaseOutageIntegrationTest` hält den PostgreSQL-Container an
  (`docker pause` über Testcontainers), sendet 20 Nachrichten, wartet 8 s und lässt ihn wieder
  laufen. Danach stehen alle 20 in der Tabelle, und `chat.dlq` ist leer. Angehalten statt gestoppt,
  damit der Container seinen Port behält.

**Commit:** `feat: bei Datenbankausfall warten und denselben Stapel wiederholen`

---

## Task 8: Einzelweg, wenn die Datenbank eine Zeile ablehnt

**Setzt um:** Spezifikation 3.7

**Dateien:**
- Ändern: `.../message/BatchConsumer.java`
- Ändern: `.../message/BatchConsumerIntegrationTest.java`

**Was entsteht:** Schlägt `writeBatch` mit einem Fehler fehl, der **kein** Verbindungsproblem ist,
schreibt der Verbraucher jede Nachricht des Stapels einzeln, jede über dieselbe Warteschleife aus
Task 7. Gelingt es, wird sie bestätigt. Schlägt es wieder fehl, wird sie abgelehnt und landet in
`chat.dlq`.

**Test:** Eine Nachricht, deren `content` ein Nullbyte (`\u0000`) enthält, ist gültiges JSON, aber
PostgreSQL lehnt sie ab. Zusammen mit zwei normalen Nachrichten gesendet: Die zwei normalen stehen
in der Tabelle, `chat.dlq` hat 1.

**Commit:** `feat: Stapel einzeln schreiben, wenn die Datenbank eine Zeile ablehnt`

---

## Task 9: Docker und Compose

**Setzt um:** Spezifikation 4.5, 4.6

**Dateien:**
- Erstellen: `batch-writer/Dockerfile` — mehrstufig wie beim `chat-service`, ohne `EXPOSE`
- Ändern: `docker-compose.yml` — Dienste `postgres` und `batch-writer`, Volume `postgres-data`
- Ändern: `.env.example` — `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`

**Was entsteht:** Die zwei Dienste genau wie in Spezifikation 4.6 beschrieben: kein `ports:`, kein
`container_name`, Healthcheck mit `pg_isready`, `depends_on` mit `service_healthy`,
`restart: unless-stopped` beim `batch-writer`.

Die eigene `.env` muss die drei neuen Variablen auch bekommen: `.env` löschen und neu aus
`.env.example` kopieren.

**Prüfen (A2):** `docker compose up -d --build`, dann `docker compose ps`. Alle vier Dienste laufen,
`postgres` und `rabbitmq` sind `healthy`, in der Spalte PORTS steht nirgends `->`.

**Commit:** `chore: postgres und batch-writer in docker-compose abbilden`

---

## Task 10: Szenario-Skripte

**Setzt um:** Spezifikation 5

**Dateien:**
- Erstellen: `scripts/send.sh`, `scripts/publish-twice.sh`, `scripts/peek-persist.sh`
- Erstellen: `.gitattributes` mit `*.sh text eol=lf`

**Was entsteht:** Die drei Skripte aus Spezifikation 5. Sie laufen im Container `curlimages/curl`
im Netz `chat-net` und lesen die Zugangsdaten aus `.env`. `publish-twice.sh` nutzt die
Management-API von RabbitMQ im Docker-Netz, Port 15672, der nicht nach aussen veröffentlicht ist.

**Warum `.gitattributes`:** Git macht auf Windows aus Zeilenenden gerne CRLF. Ein Shell-Skript mit
CRLF bricht im Linux-Container mit `\r: not found` ab.

**Prüfen:** A0 und A3 bis A7 aus Spezifikation 5 in dieser Reihenfolge auf dem laufenden Stack.

**Commit:** `test: Skripte zum Nachstellen der Szenarien`

---

## Task 11: README «Stand»

**Dateien:** Ändern: `README.md` — Zeilen `batch-writer` und `postgres` auf «vorhanden», Befehle für
die Szenario-Skripte ergänzen.

**Commit:** `docs: README-Stand fuer batch-writer und postgres nachfuehren`

---

## Abschluss-Prüfung

Auf einem **frischen Klon**, mit `.env` aus `.env.example`, in dieser Reihenfolge:

| Szenario | Kriterium | Wo es sich im Code entscheidet | Test |
|---|---|---|---|
| S1 | A1 | alle Tests, Testcontainers | `mvn clean test` |
| S2 | A2 | `docker-compose.yml`, `batch-writer/Dockerfile` | — |
| S3 | A3 | `BatchConsumer`, `MessageWriter` | `BatchConsumerIntegrationTest` (1200 Nachrichten) |
| S4 | A4 | Stapelgrösse und prefetch in `RabbitConfig`, ein INSERT pro Stapel in `MessageWriter` | — |
| S5 | A5 | `MessageParser` liest nur den Body, `ON CONFLICT` in `MessageWriter` | `BatchConsumerIntegrationTest` (Duplikat) |
| S6 | A6 | kein `container_name`, `IF NOT EXISTS` in `schema.sql`, eine gemeinsame Queue | — |
| S7 | A7 | `DatabaseErrors`, Warteschleife in `BatchConsumer` | `DatabaseOutageIntegrationTest` |
| S8 | A8 | Kommentare, keine Streams, `.env` in `.gitignore` | — |

Erst wenn alle Zeilen bestanden sind: `git tag bewertung-1` und `git push origin bewertung-1`.
