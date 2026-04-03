package com.redhat.kafka.diag.rag;

import com.redhat.kafka.diag.config.AgentConfig;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * HTTP client for the nomic-embed-text-v1.5 InferenceService.
 *
 * Calls POST /v1/embeddings on the vLLM CPU endpoint and returns
 * a 768-dimensional float vector for the given input text.
 *
 * Internal URL: http://nomic-embed-svc.kafka-diag-agent.svc.cluster.local:8000/v1
 * Model name in requests: /mnt/models (vLLM convention for KServe storage)
 *
 * Note: KServe creates a broken headless service on port 80 for the nomic-embed
 * InferenceService. Use the manually created nomic-embed-svc Service on port 8000.
 */
@ApplicationScoped
public class EmbeddingClient {

    private static final Logger LOG = Logger.getLogger(EmbeddingClient.class);

    @Inject
    AgentConfig config;

    private HttpClient httpClient;
    private String embeddingsUrl;

    @PostConstruct
    void init() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.embeddingsUrl = config.embed().baseUrl() + "/embeddings";
        LOG.infof("EmbeddingClient initialized — endpoint: %s", embeddingsUrl);
    }

    /**
     * Embed a single text and return its vector representation.
     *
     * @param text the input text to embed
     * @return float array of 768 dimensions
     * @throws EmbeddingException if the call fails or returns an unexpected response
     */
    public float[] embed(String text) throws EmbeddingException {
        if (text == null || text.isBlank()) {
            throw new EmbeddingException("Input text must not be blank");
        }

        // Truncate to avoid exceeding the model's max token limit (512 tokens)
        String truncated = text.length() > 2000 ? text.substring(0, 2000) : text;

        String requestBody = buildRequestBody(truncated);

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(embeddingsUrl))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .timeout(Duration.ofSeconds(30))
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new EmbeddingException(
                        "Embedding endpoint returned HTTP " + response.statusCode() +
                        ": " + response.body());
            }

            return parseEmbedding(response.body());

        } catch (EmbeddingException e) {
            throw e;
        } catch (Exception e) {
            throw new EmbeddingException("Failed to call embedding endpoint: " + e.getMessage(), e);
        }
    }

    /**
     * Embed multiple texts in a single batch request.
     * Falls back to individual calls if batch fails.
     */
    public List<float[]> embedBatch(List<String> texts) throws EmbeddingException {
        List<float[]> results = new java.util.ArrayList<>(texts.size());
        for (String text : texts) {
            results.add(embed(text));
        }
        return results;
    }

    // ----------------------------------------------------------------
    // Internal helpers
    // ----------------------------------------------------------------

    private String buildRequestBody(String text) {
        // Use Jackson ObjectMapper-style manual escaping to guarantee valid JSON
        // Cannot use ObjectMapper directly to avoid adding dependency
        StringBuilder sb = new StringBuilder();
        sb.append("{\"model\":\"");
        sb.append(config.embed().modelName());
        sb.append("\",\"input\":[\"");
        // Escape all special characters properly
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default   -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append("\"]}");
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private float[] parseEmbedding(String json) throws EmbeddingException {
        try {
            // Parse: {"object":"list","data":[{"object":"embedding","embedding":[...],"index":0}],...}
            int dataStart = json.indexOf("\"embedding\":[");
            if (dataStart == -1) {
                throw new EmbeddingException("No 'embedding' field found in response: " + json);
            }

            int arrayStart = json.indexOf('[', dataStart + 12);
            int arrayEnd   = json.indexOf(']', arrayStart);
            if (arrayStart == -1 || arrayEnd == -1) {
                throw new EmbeddingException("Could not parse embedding array from response");
            }

            String arrayContent = json.substring(arrayStart + 1, arrayEnd);
            String[] parts = arrayContent.split(",");
            float[] vector = new float[parts.length];
            for (int i = 0; i < parts.length; i++) {
                vector[i] = Float.parseFloat(parts[i].trim());
            }

            return vector;

        } catch (EmbeddingException e) {
            throw e;
        } catch (Exception e) {
            throw new EmbeddingException("Failed to parse embedding response: " + e.getMessage(), e);
        }
    }

    // ----------------------------------------------------------------
    // Exception
    // ----------------------------------------------------------------

    public static class EmbeddingException extends Exception {
        public EmbeddingException(String message) {
            super(message);
        }
        public EmbeddingException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}