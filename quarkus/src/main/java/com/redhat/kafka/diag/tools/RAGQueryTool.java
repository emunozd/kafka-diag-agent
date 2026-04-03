package com.redhat.kafka.diag.tools;

import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

/**
 * RAG Query Tool — implementado en Fase 3.
 * En Fase 2 retorna un placeholder para no bloquear el agente.
 */
@ApplicationScoped
public class RAGQueryTool {

    private static final Logger LOG = Logger.getLogger(RAGQueryTool.class);

    @Tool("Search the official Red Hat AMQ Streams and Strimzi documentation knowledge base " +
          "for information relevant to the user's question. " +
          "Use this to get configuration details, best practices, and troubleshooting guidance.")
    public String queryDocumentation(String query) {
        // TODO Fase 3: implementar búsqueda real en ChromaDB
        LOG.debugf("RAG query (placeholder): %s", query);
        return "[RAG not yet implemented — will be available in Fase 3. " +
               "Query was: " + query + "]";
    }
}
