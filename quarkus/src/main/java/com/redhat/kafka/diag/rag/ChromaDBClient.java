package com.redhat.kafka.diag.rag;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * ChromaDB REST client — Phase 3.
 *
 * Wraps the ChromaDB HTTP API v2. Used by the RAGQueryTool to search
 * for document chunks similar to the user's question.
 *
 * ChromaDB API v2 base path: /api/v2/
 * Key endpoints used:
 *   GET  /api/v2/collections                    — list collections
 *   POST /api/v2/collections                    — create collection
 *   POST /api/v2/collections/{id}/query         — similarity search
 *   POST /api/v2/collections/{id}/add           — insert chunks
 *   GET  /api/v2/collections/{id}/get           — get by ID (used for SHA tracking)
 *   POST /api/v2/collections/{id}/upsert        — upsert chunks or SHA records
 *
 * Note: ChromaDB uses /api/v2/ — /api/v1/ is deprecated in recent versions.
 */
@ApplicationScoped
public class ChromaDBClient {
    // TODO Phase 3: implement ChromaDB API v2 REST client
}
