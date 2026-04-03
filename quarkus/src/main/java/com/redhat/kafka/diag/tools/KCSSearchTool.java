package com.redhat.kafka.diag.tools;

import com.redhat.kafka.diag.config.AgentConfig;
import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Red Hat KCS (Knowledge Centered Support) search tool.
 *
 * Two operating modes:
 * - With offline token: queries the Red Hat Customer Portal API (Phase 5)
 * - Without token (fallback): returns a pre-built search URL for manual access
 *
 * The token is optional. If RH_OFFLINE_TOKEN is not set in the OCP Secret,
 * the app starts normally and this tool always uses fallback mode.
 *
 * Fallback mode is sufficient in most cases — it gives the user a direct
 * link to relevant KCS solutions with the right search terms.
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

        // If no token is configured, use fallback mode directly
        if (config.kcs().offlineToken() == null || config.kcs().offlineToken().isBlank()) {
            return buildFallbackResponse(query, searchUrl);
        }

        // TODO Phase 5: implement real Customer Portal API search
        // For now, return fallback even when a token is present
        return buildFallbackResponse(query, searchUrl);
    }

    private String buildFallbackResponse(String query, String searchUrl) {
        return String.format(
                "KCS search for: \"%s\"\n\n" +
                "Open this URL to find relevant Red Hat solutions:\n%s\n\n" +
                "Suggested additional search terms:\n" +
                "- site:access.redhat.com amq streams %s\n" +
                "- site:access.redhat.com strimzi %s",
                query, searchUrl,
                query.toLowerCase(),
                query.toLowerCase()
        );
    }
}
