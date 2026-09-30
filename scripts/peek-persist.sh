#!/bin/sh
# Zeigt die erste Nachricht in chat.persist, ohne sie zu entfernen
# (ackmode ack_requeue_true: lesen und sofort zuruecklegen).
# Damit laesst sich pruefen, was der chat-service wirklich sendet (A0).
#
# Vorher den batch-writer stoppen, sonst ist die Queue leer:
#   docker compose stop batch-writer
#
# Aufruf: sh /scripts/peek-persist.sh

URL="http://rabbitmq:15672/api/queues/%2F/chat.persist/get"

curl -s -u "$RABBITMQ_USER:$RABBITMQ_PASSWORD" -X POST "$URL" \
  -H "Content-Type: application/json" \
  -d '{"count":1,"ackmode":"ack_requeue_true","encoding":"auto"}'
echo
