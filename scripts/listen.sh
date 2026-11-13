#!/bin/sh
# Haengt sich als Client an GET /stream einer bestimmten chat-service-Instanz,
# hoert eine Weile zu und zaehlt die empfangenen Nachrichten (Zeilen "id:").
# Danach zeigt es die ID der letzten Nachricht, die man fuer S7 als
# Last-Event-ID braucht, und ob ein resync-Ereignis kam.
#
# Aufruf (im Docker-Netz chat-net):
#   sh /scripts/listen.sh <instanz> <raum-id> <sekunden> [last-event-id]
#   sh /scripts/listen.sh it3c-m321-chat-service-1 5e2a9c10-1111-4d7e-8a3b-2c4d6e8f0a1b 60
#
# Spezifikation Live-Zustellung, Abschnitt 6 (S3, S5, S6, S7).

TARGET="$1"
ROOM_ID="$2"
SECONDS_TO_LISTEN="$3"
LAST_EVENT_ID="$4"

if [ -z "$TARGET" ] || [ -z "$ROOM_ID" ] || [ -z "$SECONDS_TO_LISTEN" ]; then
  echo "Aufruf: listen.sh <instanz> <raum-id> <sekunden> [last-event-id]"
  exit 1
fi

URL="http://$TARGET:8080/stream?roomId=$ROOM_ID"
OUTPUT="/tmp/stream.txt"

# -N: nicht puffern, jede Zeile sofort. --max-time beendet das Zuhoeren.
# curl meldet beim Abbruch durch --max-time einen Fehler; das ist hier gewollt.
if [ -n "$LAST_EVENT_ID" ]; then
  curl -N -s --max-time "$SECONDS_TO_LISTEN" -H "Last-Event-ID: $LAST_EVENT_ID" "$URL" > "$OUTPUT"
else
  curl -N -s --max-time "$SECONDS_TO_LISTEN" "$URL" > "$OUTPUT"
fi

COUNT=$(grep -c "^id:" "$OUTPUT")
RESYNC=$(grep -c "^event:resync" "$OUTPUT")
LAST_ID=$(grep "^id:" "$OUTPUT" | tail -n 1 | cut -c 4-)

echo "Nachrichten empfangen: $COUNT"
echo "resync-Ereignisse:     $RESYNC"
echo "letzte id:             $LAST_ID"
