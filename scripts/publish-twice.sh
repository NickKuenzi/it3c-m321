#!/bin/sh
# Szenario S5: legt dieselbe Nachricht zweimal direkt in chat.persist,
# nur mit content_type: application/json und ohne __TypeId__.
#
# Das geht ueber die Management-API von RabbitMQ (Port 15672). Der Port ist
# nicht nach aussen veroeffentlicht, deshalb laeuft das Skript im Docker-Netz.
#
# Die id ist fest. So bleibt es auch bei mehrfachem Aufruf bei einer Zeile.
#
# Aufruf: sh /scripts/publish-twice.sh

URL="http://rabbitmq:15672/api/exchanges/%2F/amq.default/publish"

# Der Standard-Exchange heisst in der Management-API "amq.default".
# routing_key = Name der Queue, also direkt in chat.persist.
publish_once() {
  curl -s -u "$RABBITMQ_USER:$RABBITMQ_PASSWORD" -X POST "$URL" \
    -H "Content-Type: application/json" --data-binary @- <<'JSON'
{
  "properties": {"content_type": "application/json"},
  "routing_key": "chat.persist",
  "payload": "{\"id\":\"55555555-5555-4555-8555-555555555555\",\"roomId\":\"5e2a9c10-1111-4d7e-8a3b-2c4d6e8f0a1b\",\"senderId\":\"szenario\",\"senderName\":\"Szenario S5\",\"content\":\"S5-duplikat\",\"sentAt\":\"2026-09-30T12:00:00Z\"}",
  "payload_encoding": "string"
}
JSON
  echo
}

# Antwort je Aufruf: {"routed":true}, wenn die Nachricht in der Queue liegt.
publish_once
publish_once
