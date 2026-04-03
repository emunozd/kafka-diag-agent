package com.redhat.kafka.diag.rag;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * PDF Watcher — implementado en Fase 5.
 * Background thread que monitorea /pdfdata cada N segundos.
 * Detecta PDFs nuevos o modificados via SHA-256 y los indexa
 * automáticamente sin reiniciar la app.
 */
@ApplicationScoped
public class PDFWatcher {
    // TODO Fase 5: implementar watcher dinámico con @Scheduled
}
