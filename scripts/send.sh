#!/bin/sh
# Schickt Nachrichten per POST /messages an den chat-service, genau wie es
# spaeter das web-gateway tut. Der Text ist <praefix>-1, <praefix>-2, ...
# So laesst sich jedes Szenario in der Tabelle an seinem Praefix zaehlen.
#
# Aufruf (im Docker-Netz chat-net):
#   sh /scripts/send.sh S3 1000
#   sh /scripts/send.sh S3 200 it3c-m321-chat-service-1
#   sh /scripts/send.sh S3 200 it3c-m321-chat-service-2 <raum-id>
#
# Der dritte Wert ist optional: der Name einer bestimmten Instanz. Ohne ihn
# geht es an "chat-service", und Docker waehlt irgendeine Instanz. Fuer S3
# und S6 muss man gezielt an eine Instanz senden koennen
# (Spezifikation Live-Zustellung, Abschnitt 6).
# Der vierte Wert ist optional: die Raum-ID. Ohne ihn gilt der feste Raum A.

PREFIX="$1"
COUNT="$2"
TARGET="${3:-chat-service}"
ROOM_ID="${4:-5e2a9c10-1111-4d7e-8a3b-2c4d6e8f0a1b}"

if [ -z "$PREFIX" ] || [ -z "$COUNT" ]; then
  echo "Aufruf: send.sh <praefix> <anzahl> [instanz] [raum-id]"
  exit 1
fi

URL="http://$TARGET:8080/messages"

accepted=0
failed=0
i=1
while [ "$i" -le "$COUNT" ]; do
  # Absender = Instanz, damit man in S3 die Reihenfolge pro Absender pruefen kann.
  BODY="{\"roomId\":\"$ROOM_ID\",\"senderId\":\"szenario-$TARGET\",\"senderName\":\"Szenario $PREFIX\",\"content\":\"$PREFIX-$i\"}"
  # Nur den HTTP-Statuscode ausgeben, die Antwort selbst verwerfen.
  # --max-time: Haengt die Instanz (S6), bricht curl nach 10 s ab statt ewig zu warten.
  STATUS=$(curl -s -o /dev/null -w "%{http_code}" --max-time 10 -X POST "$URL" \
    -H "Content-Type: application/json" -d "$BODY")
  if [ "$STATUS" = "202" ]; then
    accepted=$((accepted + 1))
  else
    failed=$((failed + 1))
    echo "Nachricht $PREFIX-$i: HTTP $STATUS"
  fi
  i=$((i + 1))
done

echo "$PREFIX: $accepted angenommen (202), $failed fehlgeschlagen"
