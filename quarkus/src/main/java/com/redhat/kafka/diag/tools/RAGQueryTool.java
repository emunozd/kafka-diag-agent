package com.redhat.kafka.diag.tools;

import com.redhat.kafka.diag.config.AgentConfig;
import com.redhat.kafka.diag.rag.ChromaDBClient;
import com.redhat.kafka.diag.rag.EmbeddingClient;
import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;

/**
 * RAG Query Tool — Phase 3.
 *
 * Searches the official Red Hat AMQ Streams and Strimzi documentation knowledge
 * base for information relevant to the user's question.
 *
 * Flow:
 *   1. Embed the query using nomic-embed-text-v1.5
 *   2. Search ChromaDB for the most similar document chunks (top-k)
 *   3. Return the chunks with version and source metadata
 *
 * The agent uses the returned context to enrich its diagnosis with accurate,
 * version-specific information from the official documentation.
 */
@ApplicationScoped
public class RAGQueryTool {

    private static final Logger LOG = Logger.getLogger(RAGQueryTool.class);

    @Inject
    EmbeddingClient embeddingClient;

    @Inject
    ChromaDBClient chromaClient;

    @Inject
    AgentConfig config;

    @Tool("Search the official Red Hat AMQ Streams and Strimzi documentation knowledge base " +
          "for information relevant to the user's question. " +
          "Use this to get configuration details, best practices, and troubleshooting guidance. " +
          "Always call this tool when the user asks about configuration, tuning, or known issues.")
    public String queryDocumentation(String query) {
        LOG.infof("RAG query: %s", query);

        // Step 1: embed the query
        float[] queryVector;
        try {
            queryVector = embeddingClient.embed(query);
        } catch (EmbeddingClient.EmbeddingException e) {
            LOG.warnf("Failed to embed query '%s': %s", query, e.getMessage());
            return "[RAG unavailable — embedding service error: " + e.getMessage() +
                   ". Please rely on live cluster data for this response.]";
        }

        // Step 2: search ChromaDB
        List<ChromaDBClient.SearchResult> results;
        try {
            results = chromaClient.query(queryVector, config.chroma().topK());
        } catch (ChromaDBClient.ChromaException e) {
            LOG.warnf("ChromaDB query failed: %s", e.getMessage());
            return "[RAG unavailable — knowledge base not ready yet. " +
                   "PDF indexing may still be in progress. " +
                   "Please rely on live cluster data for this response.]";
        }

        if (results.isEmpty()) {
            return "[No relevant documentation found for: \"" + query + "\". " +
                   "The knowledge base may still be indexing. " +
                   "Please rely on live cluster data for this response.]";
        }

        // Step 3: format results for the agent
        return formatResults(query, results);
    }

    private String formatResults(String query, List<ChromaDBClient.SearchResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Documentation context for: \"").append(query).append("\" ===\n\n");

        for (int i = 0; i < results.size(); i++) {
            ChromaDBClient.SearchResult r = results.get(i);
            sb.append("--- Source ").append(i + 1).append(" ---\n");

            // Extract version and filename from metadata JSON
            String version  = extractMetaField(r.metadata(), "version");
            String filename = extractMetaField(r.metadata(), "filename");
            String page     = extractMetaField(r.metadata(), "page");

            if (!version.isEmpty())  sb.append("Version:  ").append(version).append("\n");
            if (!filename.isEmpty()) sb.append("Document: ").append(filename).append("\n");
            if (!page.isEmpty())     sb.append("Page:     ").append(page).append("\n");

            sb.append("\n").append(r.text()).append("\n\n");
        }

        sb.append("=== End of documentation context ===");
        return sb.toString();
    }

    private String extractMetaField(String metadataJson, String field) {
        if (metadataJson == null || metadataJson.isBlank()) return "";
        String key = "\"" + field + "\":\"";
        int start = metadataJson.indexOf(key);
        if (start == -1) {
            // Try numeric field (e.g. page number)
            key = "\"" + field + "\":";
            start = metadataJson.indexOf(key);
            if (start == -1) return "";
            int valueStart = start + key.length();
            int valueEnd = metadataJson.indexOf(',', valueStart);
            if (valueEnd == -1) valueEnd = metadataJson.indexOf('}', valueStart);
            if (valueEnd == -1) return "";
            return metadataJson.substring(valueStart, valueEnd).trim();
        }
        int valueStart = start + key.length();
        int valueEnd   = metadataJson.indexOf('"', valueStart);
        if (valueEnd == -1) return "";
        return metadataJson.substring(valueStart, valueEnd);
    }
}