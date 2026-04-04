package com.redhat.kafka.diag.rag;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

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
 */
@ApplicationScoped
public class PDFWatcher {

    private static final Logger LOG = Logger.getLogger(PDFWatcher.class);

    @Inject
    PDFIndexer indexer;

    @ConfigProperty(name = "kafka.diag.pdf.base-path", defaultValue = "/pdfdata")
    String basePath;

    @ConfigProperty(name = "kafka.diag.pdf.watcher.interval", defaultValue = "60")
    long intervalSeconds;

    @ConfigProperty(name = "kafka.diag.pdf.watcher.enabled", defaultValue = "true")
    boolean enabled;

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "pdf-watcher");
                t.setDaemon(true);
                return t;
            });

    void onStart(@Observes StartupEvent event) {
        start();
    }

    void onStop(@Observes ShutdownEvent event) {
        stop();
    }

    /**
     * Start the watcher after startup.
     * Uses a scheduler instead of @Scheduled to avoid pulling in the
     * quarkus-scheduler extension.
     */
    public void start() {
        if (!enabled) {
            LOG.info("PDF watcher is disabled — skipping");
            return;
        }

        Path watchPath = Path.of(basePath);
        if (!Files.exists(watchPath)) {
            LOG.warnf("PDF base path does not exist: %s — watcher not started", basePath);
            return;
        }

        LOG.infof("PDF watcher started — scanning %s every %ds", basePath, intervalSeconds);

        // Delay first scan by 10x the interval to let the startup indexing finish first
        scheduler.scheduleAtFixedRate(
                this::scan,
                intervalSeconds * 10,
                intervalSeconds,
                TimeUnit.SECONDS
        );
    }

    /**
     * Scan the base path for new or changed PDFs.
     * Delegates to PDFIndexer which already handles SHA-256 comparison.
     */
    private void scan() {
        Path watchPath = Path.of(basePath);
        if (!Files.exists(watchPath)) return;

        try {
            // Count PDFs before scan
            long[] counts = {0};
            Files.walkFileTree(watchPath, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (file.toString().toLowerCase().endsWith(".pdf")) {
                        counts[0]++;
                    }
                    return FileVisitResult.CONTINUE;
                }
            });

            LOG.debugf("PDF watcher scan — found %d PDF files in %s", counts[0], basePath);

            // Re-use PDFIndexer which skips unchanged files via SHA-256
            indexer.indexAll();

        } catch (IOException e) {
            LOG.warnf("PDF watcher scan failed: %s", e.getMessage());
        } catch (Exception e) {
            LOG.errorf(e, "Unexpected error in PDF watcher scan");
        }
    }

    /**
     * Stop the watcher gracefully on shutdown.
     */
    public void stop() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        LOG.info("PDF watcher stopped");
    }
}