package com.redhat.kafka.diag.rag;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * PDF Indexer — implementado en Fase 3.
 * Escanea /pdfdata recursivamente, calcula SHA-256 por archivo,
 * chunckea el contenido y lo indexa en ChromaDB con metadatos de versión.
 *
 * Estructura esperada de /pdfdata:
 *   /pdfdata/streams-3.1/*.pdf
 *   /pdfdata/streams-3.2/*.pdf
 *   ...
 *
 * Metadatos guardados por chunk:
 *   - version: extraído del nombre de la carpeta (ej: "streams-3.1")
 *   - filename: nombre del PDF
 *   - sha256: hash del archivo completo
 *   - page: número de página
 */
@ApplicationScoped
public class PDFIndexer {
    // TODO Fase 3: implementar indexación con SHA tracking y metadatos de versión
}
