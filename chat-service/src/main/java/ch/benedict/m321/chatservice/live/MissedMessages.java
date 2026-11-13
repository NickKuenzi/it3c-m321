package ch.benedict.m321.chatservice.live;

import ch.benedict.m321.chatservice.dto.ChatMessage;

import java.util.List;

/**
 * Antwort des Zwischenspeichers auf die Frage «was kam nach dieser ID?».
 *
 * Zwei Fälle müssen unterscheidbar sein (Spezifikation 2.3):
 * - Die ID ist bekannt: messages enthält alles danach, eventuell auch nichts.
 * - Die ID ist unbekannt: Dann weiss niemand, was fehlt, und der Server
 *   schickt ein resync-Ereignis.
 * Eine leere Liste allein könnte beides bedeuten. Deshalb das eigene Feld.
 *
 * @param lastIdKnown true, wenn die ID im Zwischenspeicher gefunden wurde
 * @param messages    die Nachrichten nach der ID, älteste zuerst
 */
public record MissedMessages(boolean lastIdKnown, List<ChatMessage> messages) {
}
