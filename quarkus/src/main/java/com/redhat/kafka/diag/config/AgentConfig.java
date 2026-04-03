package com.redhat.kafka.diag.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * Typed configuration mapping for the kafka-diag-agent application.
 * All values are injected from environment variables via OCP Secrets.
 */
@ConfigMapping(prefix = "kafka.diag")
public interface AgentConfig {

    Embed embed();
    Chroma chroma();
    Pdf pdf();
    Kcs kcs();

    @WithDefault("kafka-diag-agent")
    String defaultNamespace();

    /**
     * Embedding service configuration (nomic-embed-text-v1.5 via vLLM CPU).
     */
    interface Embed {
        @WithDefault("http://nomic-embed-svc.kafka-diag-agent.svc.cluster.local:8000/v1")
        String baseUrl();

        @WithDefault("/mnt/models")
        String modelName();
    }

    /**
     * ChromaDB vector store configuration.
     * Note: ChromaDB uses API v2 — /api/v1/ is deprecated.
     */
    interface Chroma {
        @WithDefault("http://chromadb.kafka-diag-agent.svc.cluster.local:8000")
        String baseUrl();

        @WithDefault("kafka-knowledge")
        String collection();

        @WithDefault("5")
        int topK();
    }

    /**
     * PDF knowledge base path. Organized by version subfolders:
     * /pdfdata/streams-3.1/, /pdfdata/streams-3.2/, etc.
     */
    interface Pdf {
        @WithDefault("/pdfdata")
        String basePath();
    }

    /**
     * Red Hat KCS (Knowledge Centered Support) configuration.
     * If offlineToken is empty, the KCS tool uses fallback mode (search URL).
     */
    interface Kcs {
        Optional<String> offlineToken();
    
        @WithDefault("https://access.redhat.com/search/#/?q={query}&documentKind=Solution")
        String searchUrl();
    }
}
