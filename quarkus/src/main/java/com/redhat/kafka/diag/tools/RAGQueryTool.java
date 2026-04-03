package com.redhat.kafka.diag.tools;

import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

/**
 * RAG Query Tool — Phase 3 placeholder.
 *
 * When fully implemented in Phase 3, this tool will:
 * 1. Embed the query using nomic-embed-text-v1.5
 * 2. Search ChromaDB for the most relevant document chunks
 * 3. Return the top-k chunks with version metadata (e.g. streams-3.1)
 *
 * The ChromaDB collection is populated by the PDFIndexer from PDFs
 * organized under /pdfdata/<version>/*.pdf
 */
@ApplicationScoped
public class RAGQueryTool {

    private static final Logger LOG = Logger.getLogger(RAGQueryTool.class);

    @Tool("Search the official Red Hat AMQ Streams and Strimzi documentation knowledge base " +
          "for information relevant to the user's question. " +
          "Use this to get configuration details, best practices, and troubleshooting guidance.")
    public String queryDocumentation(String query) {
        // TODO Phase 3: implement real ChromaDB similarity search
        LOG.debugf("RAG query (stub): %s", query);
        return "[RAG not yet implemented — available in Phase 3. Query was: " + query + "]";
    }
}
