package com.redhat.kafka.diag.rag;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Embedding client — Phase 3.
 *
 * Calls the nomic-embed-text-v1.5 InferenceService via vLLM CPU
 * to convert text into 768-dimensional embedding vectors.
 *
 * Used by:
 * - PDFIndexer: to embed document chunks during PDF indexing
 * - RAGQueryTool: to embed the user's query before ChromaDB similarity search
 *
 * Endpoint: POST /v1/embeddings
 * Model: /mnt/models (vLLM convention when loaded from KServe storage)
 * Output dimensions: 768
 *
 * Internal URL: http://nomic-embed-svc.kafka-diag-agent.svc.cluster.local:8000/v1
 * Note: KServe's headless service on port 80 does not work — use nomic-embed-svc on 8000.
 */
@ApplicationScoped
public class EmbeddingClient {
    // TODO Phase 3: implement REST client for nomic-embed vLLM endpoint
}
