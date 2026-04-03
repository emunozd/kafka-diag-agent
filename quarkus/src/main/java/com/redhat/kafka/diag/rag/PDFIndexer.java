package com.redhat.kafka.diag.rag;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * PDF Indexer — Phase 3.
 *
 * Scans the /pdfdata directory recursively, extracts text from PDF files,
 * splits it into overlapping chunks, embeds each chunk using the
 * EmbeddingClient, and stores the results in ChromaDB.
 *
 * SHA-256 tracking prevents re-indexing files that have not changed.
 * The hash is stored as a special document inside the ChromaDB collection
 * (id: __sha256::<filename>) so the hash and index are always in sync —
 * if ChromaDB is wiped, the hashes disappear with it and everything is
 * re-indexed on the next startup.
 *
 * Directory structure expected under /pdfdata:
 *   /pdfdata/<version>/<document>.pdf
 *   e.g. /pdfdata/streams-3.1/Deploying_Streams_on_OCP.pdf
 *
 * Metadata stored per chunk in ChromaDB:
 *   - version:  extracted from the parent folder name (e.g. "streams-3.1")
 *   - filename: PDF file name
 *   - sha256:   SHA-256 hash of the full PDF file
 *   - page:     page number within the document
 *
 * This metadata allows the agent to cite the exact version and document
 * when referencing information from the RAG results.
 */
@ApplicationScoped
public class PDFIndexer {
    // TODO Phase 3: implement PDF text extraction, chunking, embedding, and SHA tracking
}
