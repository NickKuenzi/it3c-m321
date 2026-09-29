-- Tabelle message nach PLANUNG.md 3.7 (Spezifikation 4.1).
-- Der batch-writer fuehrt diese Datei bei jedem Start aus. IF NOT EXISTS macht
-- das wiederholbar: Beim zweiten Start und bei einer zweiten Instanz passiert
-- nichts mehr.
CREATE TABLE IF NOT EXISTS message (
    -- Vom chat-service vergeben. Grundlage fuer ON CONFLICT (Spezifikation 3.2).
    id          UUID        PRIMARY KEY,
    -- Bewusst ohne Fremdschluessel auf room (Spezifikation 4.2).
    room_id     UUID        NOT NULL,
    sender_id   VARCHAR     NOT NULL,
    sender_name VARCHAR     NOT NULL,
    content     TEXT        NOT NULL,
    -- Zeitpunkt des Sendens, vom chat-service gesetzt. Deshalb kein DEFAULT now().
    sent_at     TIMESTAMPTZ NOT NULL
);

-- Fuer die spaetere Abfrage "die letzten 50 Nachrichten eines Raums".
CREATE INDEX IF NOT EXISTS idx_message_room_sent_at
    ON message (room_id, sent_at DESC);
