package com.redhat.kafka.diag.tools;

import com.redhat.kafka.diag.config.AgentConfig;
import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Red Hat KCS (Knowledge Centered Support) search tool — Phase 5.
 *
 * Two operating modes:
 * - With offline token: exchanges token for access token, then queries the
 *   Red Hat Customer Portal API for real KCS articles.
 * - Without token (fallback): returns a pre-built search URL for manual access.
 *
 * The token is read from the OCP Secret rh-api-credentials via the
 * RH_OFFLINE_TOKEN environment variable. It is never hardcoded.
 *
 * The offline token is exchanged for a short-lived access token on each call
 * using the Red Hat SSO token endpoint.
 */
@ApplicationScoped
public class KCSSearchTool {

    private static final Logger LOG = Logger.getLogger(KCSSearchTool.class);

    private static final String TOKEN_URL =
            "https://sso.redhat.com/auth/realms/redhat-external/protocol/openid-connect/token";
    private static final String KCS_API_URL =
        "https://api.access.redhat.com/support/search/kcs?q=%s&rows=5&language=en&fq=documentKind:Solution";

    @Inject
    AgentConfig config;

    @Tool("Search the Red Hat Knowledge Base (KCS) for known issues, solutions, and articles " +
          "related to the problem. Returns relevant article titles and URLs. " +
          "Call this after identifying a specific issue to find Red Hat official solutions.")
    public String searchKCS(String query) {
        LOG.infof("KCS search: %s", query);

        String offlineToken = config.kcs().offlineToken();

        if (offlineToken == null || offlineToken.isBlank() || "disabled".equals(offlineToken)) {
            LOG.debugf("KCS offline token not configured — using fallback mode");
            return buildFallbackResponse(query);
        }

        try {
            String accessToken = exchangeToken(offlineToken);
            return searchWithToken(query, accessToken);
        } catch (Exception e) {
            LOG.warnf("KCS API call failed: %s — falling back to URL mode", e.getMessage());
            return buildFallbackResponse(query);
        }
    }

    // ----------------------------------------------------------------
    // Token exchange
    // ----------------------------------------------------------------

    private String exchangeToken(String offlineToken) throws Exception {
        URL url = new URL(TOKEN_URL);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        conn.setDoOutput(true);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(15000);

        String body = "grant_type=refresh_token" +
                      "&client_id=rhsm-api" +
                      "&refresh_token=" + URLEncoder.encode(offlineToken, StandardCharsets.UTF_8);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }

        int status = conn.getResponseCode();
        if (status != 200) {
            String error = new String(conn.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            throw new Exception("Token exchange failed HTTP " + status + ": " + error);
        }

        String response = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        conn.disconnect();

        // Extract access_token from JSON response
        String key = "\"access_token\":\"";
        int start = response.indexOf(key);
        if (start == -1) throw new Exception("access_token not found in token response");
        int valueStart = start + key.length();
        int valueEnd = response.indexOf('"', valueStart);
        if (valueEnd == -1) throw new Exception("Malformed token response");

        return response.substring(valueStart, valueEnd);
    }

    // ----------------------------------------------------------------
    // KCS API search
    // ----------------------------------------------------------------

    private String searchWithToken(String query, String accessToken) throws Exception {
        String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
        URL url = new URL(String.format(KCS_API_URL, encodedQuery));

        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Authorization", "Bearer " + accessToken);
        conn.setRequestProperty("Accept", "application/json");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(15000);

        int status = conn.getResponseCode();
        if (status != 200) {
            String error = new String(conn.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            throw new Exception("KCS API returned HTTP " + status + ": " + error);
        }

        String response = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        conn.disconnect();

        LOG.infof("KCS API returned %d chars for query: %s", response.length(), query);
        return parseKCSResponse(query, response);
    }

    // ----------------------------------------------------------------
    // Response parsing
    // ----------------------------------------------------------------

    /**
     * Extracts titles and URLs from the KCS JSON response.
     * Parses manually to avoid adding a JSON library dependency.
     */
    private String parseKCSResponse(String query, String json) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== KCS Search Results for: \"").append(query).append("\" ===\n\n");
    
        int count = 0;
        int pos = 0;
    
        while (pos < json.length()) {
            // Find publishedTitle
            int titleIdx = json.indexOf("\"publishedTitle\":\"", pos);
            if (titleIdx == -1) break;
            int titleStart = titleIdx + 18;
            int titleEnd = json.indexOf('"', titleStart);
            if (titleEnd == -1) break;
            String title = json.substring(titleStart, titleEnd);
    
            // Find view_uri after the title
            int uriIdx = json.indexOf("\"view_uri\":\"", titleIdx);
            String articleUrl = "";
            if (uriIdx != -1 && uriIdx < titleIdx + 2000) {
                int uriStart = uriIdx + 12;
                int uriEnd = json.indexOf('"', uriStart);
                if (uriEnd != -1) articleUrl = json.substring(uriStart, uriEnd);
            }
    
            // Only include if it's a real access.redhat.com solution
            if (!title.isBlank() && articleUrl.contains("access.redhat.com/solutions")) {
                count++;
                sb.append(count).append(". **").append(title).append("**\n");
                sb.append("   ").append(articleUrl).append("\n\n");
            }
    
            pos = titleEnd + 1;
            if (count >= 5) break;
        }
    
        if (count == 0) {
            sb.append("No KCS solutions found.\n\n");
            sb.append(buildFallbackResponse(query));
        } else {
            sb.append("=== End of KCS results ===");
        }
    
        return sb.toString();
    }

    // ----------------------------------------------------------------
    // Fallback
    // ----------------------------------------------------------------

    private String buildFallbackResponse(String query) {
        String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
        String searchUrl = "https://access.redhat.com/search/#/?q=" + encodedQuery +
                           "&p=1&sort=relevant&product=Red+Hat+AMQ+Streams";
        return String.format(
                "KCS search for: \"%s\"\n\n" +
                "Search Red Hat solutions at:\n%s\n\n" +
                "Suggested search terms:\n" +
                "- site:access.redhat.com amq streams %s\n" +
                "- site:access.redhat.com strimzi %s",
                query, searchUrl,
                query.toLowerCase(),
                query.toLowerCase()
        );
    }
}