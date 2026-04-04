package com.redhat.kafka.diag.rag;

import com.redhat.kafka.diag.config.AgentConfig;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessReadBufferedFile;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * PDF Indexer — Phase 3.
 *
 * On application startup, scans /pdfdata recursively and indexes any PDF
 * that is new or has changed since the last indexing run.
 *
 * Directory structure expected:
 *   /pdfdata/<version>/<document>.pdf
 *   e.g. /pdfdata/streams-3.1/Deploying_Streams_on_OCP.pdf
 *
 * Chunk strategy:
 *   - Target size: 600 words per chunk
 *   - Overlap: 80 words between consecutive chunks
 *   - Minimum chunk size: 50 words (smaller chunks are discarded)
 *
 * Metadata stored per chunk in ChromaDB:
 *   - version:  parent folder name (e.g. "streams-3.1")
 *   - filename: PDF file name
 *   - page:     page number within the document
 *   - sha256:   SHA-256 of the full PDF file
 *
 * SHA-256 tracking:
 *   Hash is stored as a special document in ChromaDB (id: __sha256::<filename>).
 *   If ChromaDB is wiped, hashes disappear with the data and everything is
 *   re-indexed on next startup automatically.
 */
@ApplicationScoped
public class PDFIndexer {

    private static final Logger LOG = Logger.getLogger(PDFIndexer.class);

    private static final int CHUNK_WORDS    = 600;
    private static final int OVERLAP_WORDS  = 80;
    private static final int MIN_CHUNK_WORDS = 50;

    // Batch size for ChromaDB upsert calls — keeps request payload reasonable
    private static final int BATCH_SIZE = 20;

    @Inject
    AgentConfig config;

    @Inject
    EmbeddingClient embeddingClient;

    @Inject
    ChromaDBClient chromaClient;

    /**
     * Triggered on application startup.
     * Runs indexing asynchronously so it does not block the HTTP server startup.
     */
    void onStart(@Observes StartupEvent event) {
        Thread.ofVirtual().name("pdf-indexer").start(this::indexAll);
    }

    /**
     * Scan /pdfdata recursively and index any new or changed PDF files.
     * This method is safe to call multiple times — it is idempotent.
     */
    public void indexAll() {
        Path basePath = Path.of(config.pdf().basePath());

        if (!Files.exists(basePath)) {
            LOG.warnf("PDF base path does not exist: %s — skipping indexing", basePath);
            return;
        }

        LOG.infof("Starting PDF indexing from: %s", basePath);

        AtomicInteger indexed = new AtomicInteger(0);
        AtomicInteger skipped = new AtomicInteger(0);
        AtomicInteger failed  = new AtomicInteger(0);

        try (Stream<Path> paths = Files.walk(basePath)) {
            paths.filter(p -> p.toString().toLowerCase().endsWith(".pdf"))
                 .forEach(pdfPath -> {
                     try {
                         if (indexFile(pdfPath, basePath)) {
                             indexed.incrementAndGet();
                         } else {
                             skipped.incrementAndGet();
                         }
                     } catch (Exception e) {
                         LOG.errorf(e, "Failed to index PDF: %s", pdfPath);
                         failed.incrementAndGet();
                     }
                 });
        } catch (IOException e) {
            LOG.errorf(e, "Failed to scan PDF directory: %s", basePath);
            return;
        }

        LOG.infof("PDF indexing complete — indexed=%d skipped=%d failed=%d",
                indexed.get(), skipped.get(), failed.get());
    }

    /**
     * Index a single PDF file.
     *
     * @return true if the file was indexed, false if it was skipped (unchanged)
     */
    public boolean indexFile(Path pdfPath, Path basePath) throws Exception {
        String filename = pdfPath.getFileName().toString();
        String sha256   = computeSha256(pdfPath);

        // Check if the file has already been indexed with the same hash
        try {
            String storedHash = chromaClient.getFileHash(filename);
            if (sha256.equals(storedHash)) {
                LOG.debugf("Skipping unchanged file: %s", filename);
                return false;
            }
        } catch (ChromaDBClient.ChromaException e) {
            // Collection doesn't exist yet or file not found — proceed with indexing
            LOG.debugf("No existing hash for %s — will index", filename);
        }

        LOG.infof("Indexing: %s", pdfPath);

        // Extract version from the parent folder name (e.g. /pdfdata/streams-3.1/ → "streams-3.1")
        String version = extractVersion(pdfPath, basePath);

        // Extract text from PDF
        List<PageText> pages = extractTextFromPdf(pdfPath.toFile());
        if (pages.isEmpty()) {
            LOG.warnf("No text extracted from: %s — skipping", filename);
            return false;
        }

        // Split into chunks
        List<TextChunk> chunks = splitIntoChunks(pages, filename, version, sha256);
        if (chunks.isEmpty()) {
            LOG.warnf("No chunks generated from: %s — skipping", filename);
            return false;
        }

        LOG.infof("  %s → %d pages, %d chunks", filename, pages.size(), chunks.size());

        // Embed and store in batches
        List<ChromaDBClient.DocumentChunk> batch = new ArrayList<>(BATCH_SIZE);

        for (TextChunk chunk : chunks) {
            try {
                float[] vector = embeddingClient.embed(chunk.text());
                batch.add(new ChromaDBClient.DocumentChunk(
                        chunk.id(),
                        chunk.text(),
                        vector,
                        chunk.version(),
                        chunk.filename(),
                        chunk.page(),
                        chunk.sha256()
                ));

                if (batch.size() >= BATCH_SIZE) {
                    chromaClient.insertChunks(batch);
                    batch.clear();
                }
            } catch (EmbeddingClient.EmbeddingException e) {
                LOG.warnf("Failed to embed chunk %s: %s — skipping chunk", chunk.id(), e.getMessage());
            }
        }

        // Flush remaining chunks
        if (!batch.isEmpty()) {
            chromaClient.insertChunks(batch);
        }

        // Save hash only after successful indexing
        chromaClient.saveFileHash(filename, sha256);

        LOG.infof("  Indexed %s — %d chunks stored", filename, chunks.size());
        return true;
    }

    // ----------------------------------------------------------------
    // PDF text extraction
    // ----------------------------------------------------------------

    private List<PageText> extractTextFromPdf(File file) throws IOException {
        List<PageText> pages = new ArrayList<>();

        try (PDDocument doc = Loader.loadPDF(new RandomAccessReadBufferedFile(file))) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);

            int numPages = doc.getNumberOfPages();
            for (int pageNum = 1; pageNum <= numPages; pageNum++) {
                stripper.setStartPage(pageNum);
                stripper.setEndPage(pageNum);
                String text = stripper.getText(doc).trim();
                if (!text.isBlank()) {
                    pages.add(new PageText(pageNum, text));
                }
            }
        }

        return pages;
    }

    // ----------------------------------------------------------------
    // Chunking
    // ----------------------------------------------------------------

    private List<TextChunk> splitIntoChunks(
            List<PageText> pages, String filename, String version, String sha256) {

        // Combine all page text with page markers for tracking
        List<WordToken> allWords = new ArrayList<>();
        for (PageText page : pages) {
            String[] words = page.text().split("\\s+");
            for (String word : words) {
                if (!word.isBlank()) {
                    allWords.add(new WordToken(word, page.pageNum()));
                }
            }
        }

        List<TextChunk> chunks = new ArrayList<>();
        int start = 0;

        while (start < allWords.size()) {
            int end = Math.min(start + CHUNK_WORDS, allWords.size());

            List<WordToken> chunkTokens = allWords.subList(start, end);

            if (chunkTokens.size() >= MIN_CHUNK_WORDS) {
                String text = buildText(chunkTokens);
                int page = chunkTokens.get(0).page();
                String id = UUID.randomUUID().toString();

                chunks.add(new TextChunk(id, text, filename, version, page, sha256));
            }

            start += (CHUNK_WORDS - OVERLAP_WORDS);
        }

        return chunks;
    }

    private String buildText(List<WordToken> tokens) {
        StringBuilder sb = new StringBuilder();
        for (WordToken t : tokens) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(t.word());
        }
        return sb.toString();
    }

    // ----------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------

    private String computeSha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream fis = new FileInputStream(path.toFile())) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = fis.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        byte[] hash = digest.digest();
        StringBuilder hex = new StringBuilder();
        for (byte b : hash) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    private String extractVersion(Path pdfPath, Path basePath) {
        // New structure: /pdfdata/streams/3.1/doc.pdf → "streams/3.1"
        // Old structure: /pdfdata/streams-3.1/doc.pdf → "streams-3.1"
        Path parent = pdfPath.getParent();
        if (parent == null || parent.equals(basePath)) return "unknown";
    
        // Check if parent is a version folder (e.g. 3.1, 3.2.7)
        Path grandParent = parent.getParent();
        if (grandParent != null && !grandParent.equals(basePath)) {
            // Two levels deep: product/version
            return grandParent.getFileName().toString() + "/" + parent.getFileName().toString();
        }
    
        // One level deep: legacy folder name like streams-3.1
        return parent.getFileName().toString();
    }

    // ----------------------------------------------------------------
    // Value objects
    // ----------------------------------------------------------------

    private record PageText(int pageNum, String text) {}

    private record WordToken(String word, int page) {}

    private record TextChunk(
            String id,
            String text,
            String filename,
            String version,
            int page,
            String sha256
    ) {}
}