package ch.benedict.m321.batchwriter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Startpunkt des batch-writer.
 *
 * Der Dienst hat bewusst keinen Webserver und keine REST-Schnittstelle. Er
 * ist der einzige Schreiber in die Tabelle message und lebt allein davon,
 * dass er an der Queue chat.persist hängt.
 */
@SpringBootApplication
public class BatchWriterApplication {

    /** Übergibt an Spring Boot, das alle Teile des Dienstes zusammenbaut. */
    public static void main(String[] args) {
        SpringApplication.run(BatchWriterApplication.class, args);
    }
}
