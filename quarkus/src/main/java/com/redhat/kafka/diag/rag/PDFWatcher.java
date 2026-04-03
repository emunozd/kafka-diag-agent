package com.redhat.kafka.diag.rag;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * PDF Watcher — Phase 5.
 *
 * Background scheduled job that monitors /pdfdata recursively at a
 * configurable interval (default: 60 seconds).
 *
 * On each scan:
 * 1. Walks all subdirectories under /pdfdata
 * 2. For each PDF file, computes its SHA-256 hash
 * 3. Checks ChromaDB for an existing hash record for that file
 * 4. If the hash differs or does not exist, delegates to PDFIndexer
 * 5. Skips files whose hash matches — no unnecessary re-indexing
 *
 * This enables dynamic knowledge base updates:
 * - Upload a new PDF via oc cp or the Web UI
 * - The watcher detects it within the next scan interval
 * - The PDF is indexed automatically without restarting the app
 * - The agent can use the new knowledge immediately
 *
 * Interval is configurable via: kafka.diag.pdf.watcher.interval (default: 60s)
 */
@ApplicationScoped
public class PDFWatcher {
    // TODO Phase 5: implement @Scheduled watcher with recursive directory scan
}
