package com.redhat.kafka.diag.tools;

import com.redhat.kafka.diag.config.AgentConfig;
import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * KCS Search Tool.
 * Si hay offline token configurado: intenta buscar via API del Customer Portal.
 * Si no hay token (o falla): retorna URL de búsqueda para que el usuario la abra.
 */
@ApplicationScoped
public class KCSSearchTool {

    private static final Logger LOG = Logger.getLogger(KCSSearchTool.class);

    @Inject
    AgentConfig config;

    @Tool("Search the Red Hat Knowledge Base (KCS) for known issues, solutions, and articles " +
          "related to the problem. Returns relevant article titles and URLs.")
    public String searchKCS(String query) {
        LOG.debugf("KCS search: %s", query);

        String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
        String searchUrl = config.kcs().searchUrl().replace("{query}", encodedQuery);

        // Si no hay token configurado — modo fallback directo
        if (config.kcs().offlineToken() == null || config.kcs().offlineToken().isBlank()) {
            return buildFallbackResponse(query, searchUrl);
        }

        // TODO Fase 5: implementar búsqueda real via Customer Portal API
        // Por ahora retorna fallback aunque haya token
        return buildFallbackResponse(query, searchUrl);
    }

    private String buildFallbackResponse(String query, String searchUrl) {
        return String.format(
                "KCS Search for: \"%s\"\n\n" +
                "Open this URL to find relevant Red Hat solutions:\n%s\n\n" +
                "Suggested search terms for AMQ Streams issues:\n" +
                "- site:access.redhat.com amq streams %s\n" +
                "- site:access.redhat.com strimzi %s",
                query, searchUrl,
                query.toLowerCase(),
                query.toLowerCase()
        );
    }
}
