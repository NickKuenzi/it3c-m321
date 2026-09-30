#!/bin/sh
# Schickt Nachrichten per POST /messages an den chat-service, genau wie es
# spaeter das web-gateway tut. Der Text ist <praefix>-1, <praefix>-2, ...
# So laesst sich jedes Szenario in der Tabelle an seinem Praefix zaehlen.
#
# Aufruf (im Docker-Netz chat-net, siehe Spezifikation 5):
#   sh /scripts/send.sh S3 1000

PREFIX="$1"
COUNT="$2"

if [ -z "$PREFIX" ] || [ -z "$COUNT" ]; then
  echo "Aufruf: send.sh <praefix> <anzahl>"
  exit 1
fi

URL="http://chat-service:8080/messages"
ROOM_ID="5e2a9c10-1111-4d7e-8a3b-2c4d6e8f0a1b"

accepted=0
failed=0
i=1
while [ "$i" -le "$COUNT" ]; do
  BODY="{\"roomId\":\"$ROOM_ID\",\"senderId\":\"szenario\",\"senderName\":\"Szenario $PREFIX\",\"content\":\"$PREFIX-$i\"}"
  # Nur den HTTP-Statuscode ausgeben, die Antwort selbst verwerfen.
  STATUS=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$URL" \
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
