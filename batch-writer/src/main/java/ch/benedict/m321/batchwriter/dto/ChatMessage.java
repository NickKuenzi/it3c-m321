package ch.benedict.m321.batchwriter.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Eine Nachricht, wie sie der chat-service in die Queue chat.persist legt.
 *
 * Das ist die eigene Kopie des batch-writer. Der Vertrag zwischen den
 * Diensten ist das JSON, nicht diese Klasse. Ein gemeinsames Modul würde die
 * Dienste aneinanderbinden (Spezifikation 2).
 *
 * @param id         vom chat-service vergeben, Primärschlüssel in der Tabelle
 * @param roomId     der Raum der Nachricht
 * @param senderId   die sub-Kennung des Absenders aus Keycloak
 * @param senderName der Anzeigename des Absenders
 * @param content    der Text der Nachricht
 * @param sentAt     vom chat-service gesetzt, der Zeitpunkt des Annehmens
 */
public record ChatMessage(
        UUID id,
        UUID roomId,
        String senderId,
        String senderName,
        String content,
        Instant sentAt) {
}
