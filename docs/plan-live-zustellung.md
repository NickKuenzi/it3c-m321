# Umsetzungsplan: Live-Zustellung

Grundlage: `docs/spec-live-zustellung.md`

## Arbeitsweise

Die KI schreibt den Code, Schritt für Schritt nach diesem Plan. Pro Schritt bekommt sie genau den Auftrag, der hier steht. Ich lese, was sie liefert, lasse den Test laufen und korrigiere, bevor ich committe. Was ich geprüft und geändert habe, trage ich nach dem Schritt unter «Geprüft und korrigiert» ein.

Jeder Schritt ist ein eigener Commit. Commits mit Code der KI tragen die Zeile `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

Dieser Plan wird vor dem ersten Schritt gepusht. Danach ändere ich nichts mehr am bestehenden Text. Muss ich umplanen, bleibt der alte Text stehen und die Änderung kommt unten unter «Änderungen am Plan» dazu, mit Grund und in einem eigenen Commit.

## Übersicht

| # | Schritt | Szenario |
|---|---|---|
| 1 | Live-Queue pro Instanz | S4, S6 |
| 2 | Zwischenspeicher pro Raum | S7 |
| 3 | Verbraucher an der Live-Queue | S3, S5 |
| 4 | SSE-Endpunkt und Raumfilter | S3 |
| 5 | Nachholen mit Last-Event-ID | S7 |
| 6 | Test mit zwei Instanzen | S3 |
| 7 | Sendeskript und README | S3, S8 |

**Warum diese Reihenfolge:**

Die Umsetzung erfolgt von innen nach aussen. Zuerst werden die benötigten RabbitMQ-Queues und der Zwischenspeicher erstellt. Danach wird der Nachrichtenempfang umgesetzt und über den SSE-Endpunkt nach aussen verfügbar gemacht. Zum Schluss folgen die Sonderfälle und die Tests mit mehreren Instanzen.

---

## Schritt 1: Live-Queue pro Instanz

**Ziel:** Jede chat-service-Instanz legt beim Start ihre eigene Queue `chat.live.<instanz>` an und bindet sie an `chat.delivery`. Die Queue ist exklusiv und Auto-Delete, hat höchstens 1000 Nachrichten (älteste fliegen raus) und eine Nachrichten-TTL von 30 Sekunden. Die Werte stehen in `application.yml`.

**Warum an dieser Stelle:** Die Live-Queue ist die Grundlage für die gesamte Live-Zustellung. Jede Instanz benötigt eine eigene Queue, damit sie alle Nachrichten unabhängig voneinander empfangen kann. Die weiteren Schritte bauen auf dieser Queue auf.

**Auftrag an die KI:** Erstelle im chat-service eine eigene Live-Queue pro Instanz und binde diese an den Exchange `chat.delivery`. Die Queue soll exklusiv sein, sich automatisch löschen und die vorgegebenen Einstellungen für Nachrichtenlimit und TTL verwenden. Lies dazu die Spezifikation und halte dich besonders an die Vorgaben zur RabbitMQ-Konfiguration. Die Werte sollen in der `application.yml` konfigurierbar sein. Schreibe einen Integrationstest mit Testcontainers, der die Queue und ihre Einstellungen überprüft. Halte dich an die `CLAUDE.md` und die Spezifikation.

**Test:** Integrationstest mit echtem RabbitMQ (Testcontainers): Nach dem Start existiert eine Queue, deren Name mit `chat.live.` beginnt, sie ist an `chat.delivery` gebunden und hat die Einstellungen 1000, `drop-head` und 30000 ms.

**Commit:** `feat: eigene Live-Queue pro Instanz am Exchange chat.delivery`

**Geprüft und korrigiert:** Test `mvn -pl chat-service -am test` grün (17 Tests, davon 3 neue in
`LiveQueueIntegrationTest`). In `RabbitConfig.liveQueue` kontrolliert: exklusiv, Auto-Delete,
höchstens 1000 Nachrichten mit `drop-head` und 30 s TTL, die Werte kommen aus `application.yml`.
Das stimmt mit Spezifikation 3.3, 3.5 und 4.2 überein. Der Name besteht aus Rechnername und
zufälligem Teil und ist damit auch bei zwei Instanzen auf demselben Rechner eindeutig.
Die KI hat die Queue zusätzlich als nicht dauerhaft angelegt. Das habe ich übernommen, weil
eine Live-Queue keinen Neustart des Brokers überleben muss. Sonst nichts geändert.

---

## Schritt 2: Zwischenspeicher pro Raum

**Ziel:** Eine Klasse, die pro Raum die letzten 100 Nachrichten in Eingangsreihenfolge speichert und alle Nachrichten **nach** einer bestimmten ID zurückgibt. Ist die ID unbekannt, ist das erkennbar.

**Warum an dieser Stelle:** Der Zwischenspeicher hängt von nichts anderem ab und lässt sich unabhängig von RabbitMQ testen. Die späteren Schritte benötigen ihn, um Nachrichten zwischenzuspeichern und bei einer erneuten Verbindung nachzuliefern.

**Auftrag an die KI:** Baue im chat-service eine Klasse, die pro Raum die letzten 100 Nachrichten in der Reihenfolge ihres Eingangs speichert. Sie soll alle Nachrichten nach einer bestimmten Nachrichten-ID zurückgeben und erkennbar machen, wenn die ID unbekannt ist. Schreibe dazu einen Unit-Test ohne Container. Achte darauf, dass die Räume voneinander getrennt bleiben. Halte dich an die `CLAUDE.md` und an Abschnitt 4.1 der Spezifikation.

**Test:** Unit-Test ohne Container: Reihenfolge bleibt erhalten; die 101. Nachricht verdrängt die älteste; «nach ID X» liefert genau die späteren; unbekannte ID wird erkannt; Räume sind getrennt.

**Commit:** `feat: Zwischenspeicher der letzten 100 Nachrichten pro Raum`

**Geprüft und korrigiert:** Test `mvn -pl chat-service -am test` grün (23 Tests, davon 6 neue in
`RoomMessageBufferTest`). In `RoomMessageBuffer` kontrolliert: pro Raum eine eigene Schlange,
neue Nachrichten hinten, ab der 101. fällt vorne die älteste weg, die Grösse 100 kommt aus
`application.yml` (Spezifikation 4.1). `findMessagesAfter` liefert die Nachrichten nach der ID in
Eingangsreihenfolge. Zusätzlich zum Auftrag hat die KI zwei Dinge eingebaut, die ich übernommen
habe: die Klasse `MissedMessages`, weil eine leere Liste allein nicht zeigt, ob die ID unbekannt
war (nötig für `resync`, Spezifikation 2.3), und `synchronized` auf beiden Methoden, weil der
Verbraucher schreibt, während HTTP-Anfragen lesen.

---

## Schritt 3: Verbraucher an der Live-Queue

**Ziel:** Die Instanz liest jede Nachricht aus ihrer Live-Queue und legt sie in den Zwischenspeicher. Genau ein Verbraucher pro Instanz, damit die Reihenfolge erhalten bleibt (Spezifikation 3.2).

**Warum an dieser Stelle:** Die Queue und der Zwischenspeicher sind jetzt vorhanden. Als Nächstes müssen die Nachrichten automatisch aus RabbitMQ gelesen und im Zwischenspeicher abgelegt werden. Damit ist die Grundlage für die spätere Zustellung an die Clients geschaffen.

**Auftrag an die KI:** Implementiere einen Nachrichtenverbraucher im chat-service, der Nachrichten aus der eigenen Live-Queue liest und im passenden Zwischenspeicher ablegt. Es darf nur einen Verbraucher pro Instanz geben, damit die Reihenfolge der Nachrichten erhalten bleibt. Verwende das Nachrichtenformat aus der Spezifikation und beachte insbesondere Abschnitt 3.2. Schreibe einen Integrationstest mit echtem RabbitMQ, der überprüft, ob eine gesendete Nachricht im Zwischenspeicher landet. Halte dich an die `CLAUDE.md`.

**Test:** Integrationstest mit echtem RabbitMQ: Eine Nachricht wird an `chat.delivery` gesendet und steht danach im Zwischenspeicher ihres Raums.

**Commit:** `feat: Nachrichten aus der Live-Queue in den Zwischenspeicher legen`

**Geprüft und korrigiert:** Erster Testlauf rot: `ChatServiceApplicationTest.contextLoads` scheiterte
mit `ACCESS_REFUSED`. Ursache: Seit Schritt 3 hört der Dienst beim Start an der Live-Queue und
braucht dafür RabbitMQ. Der alte Test hatte keinen eigenen Broker und verband sich mit dem, was
auf meinem Rechner auf localhost:5672 lief. Korrigiert: Der Test bekommt einen eigenen
RabbitMQ-Container, damit er auf jedem Rechner gleich läuft. Danach grün (24 Tests, davon 1 neuer
in `LiveMessageListenerIntegrationTest`). In `LiveMessageListener` kontrolliert: `receive` hört auf
die Live-Queue aus Schritt 1 (Name über `#{liveQueue.name}`, weil er einen Zufallsteil hat) und
legt jede Nachricht in den Zwischenspeicher, auch ohne Client im Raum (Spezifikation 4.1).
`concurrency = "1"` sorgt für genau einen Verbraucher pro Instanz (Spezifikation 3.2).

---

## Schritt 4: SSE-Endpunkt und Raumfilter

**Ziel:** `GET /stream?roomId=…` mit `text/event-stream`. Fehlt die roomId oder ist sie keine UUID: `400`. Jede neue Nachricht geht nur an die Verbindungen ihres Raums, als Ereignis mit `id:` (Nachrichten-ID) und `data:` (JSON wie in `chat.persist`). Getrennte Verbindungen werden entfernt.

**Warum an dieser Stelle:** Der Nachrichtenempfang und die Speicherung funktionieren bereits. Jetzt kann die Verbindung zwischen Server und Client umgesetzt werden. Der SSE-Endpunkt ermöglicht es den Clients, neue Nachrichten direkt zu empfangen, ohne ständig selbst beim Server nachfragen zu müssen.

**Auftrag an die KI:** Implementiere im chat-service den SSE-Endpunkt `GET /stream?roomId=…`. Der Endpunkt soll eine dauerhafte Verbindung mit `text/event-stream` herstellen und neue Nachrichten an die verbundenen Clients weiterleiten. Achte darauf, dass jeder Client nur Nachrichten aus seinem eigenen Raum erhält. Ungültige oder fehlende roomIds müssen mit HTTP 400 beantwortet werden. Die Ereignisse sollen eine Nachrichten-ID und die JSON-Daten enthalten. Beende und entferne Verbindungen, wenn ein Client die Verbindung trennt. Orientiere dich an der Spezifikation für die SSE-Zustellung. Schreibe einen Integrationstest mit echtem RabbitMQ und HTTP. Halte dich an die `CLAUDE.md`.

**Test:** Integrationstest mit echtem RabbitMQ und echtem HTTP: Ein Client in Raum A erhält eine Nachricht für Raum A mit `id:` und `data:`; ein Client in Raum B erhält sie nicht; ungültige `roomId` gibt `400`.

**Commit:** `feat: SSE-Endpunkt /stream stellt Nachrichten pro Raum zu`

**Geprüft und korrigiert:** Erster Testlauf hing: Spring schickt die SSE-Antwort erst mit dem
ersten Ereignis, der Testclient wartete in einem leeren Raum ewig. Korrigiert: `register` schickt
beim Anmelden sofort einen SSE-Kommentar `:connected`, den Clients ignorieren; der Testclient
bekam ein Zeitlimit von 10 s, damit so ein Fehler den Test rot macht statt ihn hängen zu lassen.
Danach grün (27 Tests, davon 3 neue in `StreamControllerIntegrationTest`). In `StreamController`
kontrolliert: `GET /stream` mit `text/event-stream`, roomId als UUID, damit Spring bei fehlender
oder ungültiger roomId selbst 400 antwortet (Spezifikation 2.2); die Verbindung meldet sich bei
Ende, Zeitablauf oder Fehler ab (Spezifikation 3.7). In `LiveDelivery` kontrolliert: pro Raum
eine Liste von Verbindungen, `deliver` legt die Nachricht zuerst in den Zwischenspeicher und
schickt sie dann nur an den eigenen Raum, mit `id:` und `data:` (Spezifikation 2.2, 3.2). Alle
Methoden haben dieselbe Sperre; übernommen, weil Schritt 5 das für das gesperrte Nachholen
braucht (Spezifikation 3.6).

---

## Schritt 5: Nachholen mit Last-Event-ID

**Ziel:** Schickt der Client `Last-Event-ID`, liefert der Server zuerst alle Nachrichten danach aus dem Zwischenspeicher und dann live weiter. Nachholen und Anmelden passieren gesperrt in einem Schritt (Spezifikation 3.6). Unbekannte ID: Ereignis `resync` ohne `id:`, danach live.

**Warum an dieser Stelle:** Die normale Live-Zustellung funktioniert bereits. Jetzt wird sichergestellt, dass Clients auch Nachrichten erhalten, die während einer unterbrochenen Verbindung gesendet wurden. Dafür werden der Zwischenspeicher und der SSE-Endpunkt miteinander verbunden.

**Auftrag an die KI:** Erweitere den SSE-Endpunkt um die Unterstützung der `Last-Event-ID`. Wenn ein Client sich erneut verbindet, sollen zuerst alle Nachrichten nach der angegebenen ID aus dem Zwischenspeicher gesendet werden. Anschliessend soll die Live-Zustellung ohne Unterbrechung weiterlaufen. Das Nachholen und die Anmeldung für neue Nachrichten müssen atomar bzw. gesperrt ablaufen, damit keine Nachrichten verloren gehen oder doppelt zugestellt werden. Ist die ID unbekannt, soll ein `resync`-Ereignis ohne `id:` gesendet werden. Beachte besonders Abschnitt 3.6 der Spezifikation. Schreibe Integrationstests für beide Fälle. Halte dich an die `CLAUDE.md`.

**Test (Pflicht, S7):** Client empfängt eine Nachricht und trennt; 50 Nachrichten werden gesendet; Client verbindet neu mit `Last-Event-ID` und erhält genau diese 50, in Reihenfolge, keine doppelt, danach eine weitere live. Zusätzlich: unbekannte ID ergibt `resync`.

**Commit:** `feat: verpasste Nachrichten mit Last-Event-ID nachliefern`

**Geprüft und korrigiert:** Test `mvn -pl chat-service -am test` grün (30 Tests, davon 3 neue in
`ReconnectIntegrationTest`, darunter der Pflicht-Test für S7 mit genau 50 nachgeholten
Nachrichten). In `LiveDelivery.register` kontrolliert: erst `:connected`, dann Nachholen aus dem
Zwischenspeicher, dann Anmelden für live; alles unter derselben Sperre wie `deliver`, damit
zwischen Nachholen und Anmelden keine Nachricht verloren geht oder doppelt kommt
(Spezifikation 3.6). Unbekannte ID gibt ein `resync`-Ereignis ohne `id:` (Spezifikation 2.3).
Zusätzlich zum Auftrag hat die KI einen ungültigen Last-Event-ID-Header (keine UUID) wie eine
unbekannte ID behandelt; übernommen, weil so ein Header zu keiner Nachricht gehören kann.

---

## Schritt 6: Test mit zwei Instanzen

**Ziel:** Ein Test startet zwei chat-service-Instanzen am selben RabbitMQ und zeigt, dass beide ihre eigene Queue haben und jede Nachricht an beide geht.

**Warum an dieser Stelle:** Die Live-Zustellung funktioniert jetzt mit einer Instanz. Als Nächstes muss überprüft werden, ob das System auch mit mehreren Instanzen korrekt arbeitet. So wird sichergestellt, dass jede Instanz alle Nachrichten erhält und unabhängig voneinander zustellen kann.

**Auftrag an die KI:** Erstelle einen Integrationstest, der zwei chat-service-Instanzen gleichzeitig mit demselben RabbitMQ verbindet. Überprüfe, dass beide Instanzen eine eigene Live-Queue besitzen und Nachrichten vom Exchange `chat.delivery` an beide Instanzen verteilt werden. Teste die Zustellung über die SSE-Endpunkte mit mehreren Clients und verschiedenen Räumen. Achte darauf, dass Nachrichten nicht doppelt an einen Client gesendet werden und die Reihenfolge pro Absender erhalten bleibt. Orientiere dich an Szenario S3 der Spezifikation. Halte dich an die `CLAUDE.md`.

**Test (Pflicht, S3):** Je ein Client in Raum A an Instanz 1 und Instanz 2, einer in Raum B. Nachrichten über beide Instanzen: Beide A-Clients erhalten alle, jede genau einmal, Reihenfolge pro Absender stimmt; der B-Client erhält keine.

**Commit:** `test: Zustellung an zwei Instanzen`

**Geprüft und korrigiert:** Test `mvn -pl chat-service -am test` grün (32 Tests, davon 2 neue in
`TwoInstancesIntegrationTest`). Kontrolliert: Der Test startet zwei vollständige chat-service-
Anwendungen am selben RabbitMQ, weil `@SpringBootTest` nur eine Anwendung startet. Die
Live-Queues haben verschiedene Namen mit je einem Verbraucher (Spezifikation 3.2). Je 200
Nachrichten über `POST /messages` an jede Instanz: Beide Clients in Raum A erhalten alle 400,
keine doppelt, die Reihenfolge pro Absender stimmt; der Client in Raum B erhält nur seine eigene
Nachricht (Szenario S3). Nichts geändert.

---

## Schritt 7: Sendeskript und README

**Ziel:** `scripts/send.sh` kann gezielt an eine bestimmte Instanz senden (für S3). Neues Skript, das als Client an einer Instanz hängt und die Ereignisse zählt. README-Tabelle «Stand» nachgeführt.

**Warum an dieser Stelle:** Die technische Umsetzung und die Tests sind abgeschlossen. Zum Schluss werden die Skripte angepasst, damit sich die verschiedenen Szenarien einfacher testen lassen. Ausserdem wird die README aktualisiert, damit der aktuelle Stand der Umsetzung dokumentiert ist.

**Auftrag an die KI:** Passe `scripts/send.sh` so an, dass Nachrichten gezielt über eine bestimmte chat-service-Instanz gesendet werden können. Erstelle zusätzlich ein Skript, das sich als SSE-Client mit einer Instanz verbindet und die empfangenen Ereignisse zählt. Aktualisiere anschliessend die README-Tabelle «Stand» entsprechend der fertigen Umsetzung. Die Skripte sollen insbesondere das Testen von Szenario S3 und S8 unterstützen. Halte dich an die `CLAUDE.md` und an die Vorgaben der Spezifikation.

**Test:** Auf dem laufenden Stack mit zwei Instanzen: Skript sendet über Instanz 1 und 2, das Zählskript zeigt die erwartete Anzahl.

**Commit:** `test: Skripte fuer die Szenarien der Live-Zustellung`

**Geprüft und korrigiert:** Auf dem laufenden Stack mit `--scale chat-service=2` getestet:
`listen.sh` an Instanz 1 in Raum A, dann je 5 Nachrichten mit `send.sh` gezielt über Instanz 1
und Instanz 2; `listen.sh` zählte 10. Damit kommt auch eine Nachricht von Instanz 2 beim Client
an Instanz 1 an. `send.sh` funktioniert ohne die neuen Werte wie bisher (Bewertung 1). Zusätzlich
zum Auftrag hat die KI `--max-time 10` in `send.sh` eingebaut, damit das Skript bei einer
angehaltenen Instanz (S6) nicht hängt; übernommen. README-Tabelle «Stand» und Abschnitt
«Live-Zustellung nachstellen» kontrolliert.

---

## Änderungen am Plan

### 13.11.2026: Kommentare über allen Testmethoden von chat-service

**Was sich ändert:** Ein zusätzlicher Commit nach Schritt 7. Alle Testmethoden in
`chat-service/src/test/`, die noch keinen Kommentar hatten, bekommen einen kurzen Kommentar.
Betroffen sind vor allem die bestehenden Tests aus der Vorlage (`SendMessageRequestTest`,
`MessageServiceTest`, `MessagePublisherIntegrationTest`, `MessageControllerIntegrationTest`,
`MessageExceptionHandlerTest`, `RabbitConfigIntegrationTest`, `ChatServiceApplicationTest`).

**Änderungsgrund:** Beim Prüfen von Szenario S8 vor der Abgabe gefunden: S8 verlangt für den
ganzen Quelltext von `chat-service/` einen Kommentar über jeder Klasse und Methode. Die neuen
Klassen aus den Schritten 1 bis 7 erfüllen das, aber einige ältere Testmethoden nicht. Der Code
selbst ändert sich nicht.

**Auftrag an die KI:** Ergänze in `chat-service/src/test/` über jeder Testmethode ohne Kommentar
einen kurzen deutschen Kommentar, der sagt, was der Test prüft. Ändere sonst nichts.

**Commit:** `docs: Kommentare ueber allen Testmethoden im chat-service`

**Geprüft und korrigiert:** *(nach dem Schritt ausfüllen)*
