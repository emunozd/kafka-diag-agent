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
import java.util.ArrayList;
import java.util.List;

/**
 * HTTP client for ChromaDB REST API v2.
 *
 * Handles collection management, document insertion, and similarity search.
 * All calls use /api/v2/ — the v1 API is deprecated in recent ChromaDB versions.
 *
 * Internal URL: http://chromadb.kafka-diag-agent.svc.cluster.local:8000
 *
 * SHA-256 tracking:
 *   Each indexed PDF stores its hash as a special document with id="__sha256::<filename>".
 *   This is atomic with the actual chunks — if ChromaDB is reset, hashes disappear
 *   with the data and everything is re-indexed on next startup.
 */
@ApplicationScoped
public class ChromaDBClient {

    private static final Logger LOG = Logger.getLogger(ChromaDBClient.class);

    @Inject
    AgentConfig config;

    private HttpClient httpClient;
    private String baseUrl;
    private String collectionName;

    // Cached collection ID to avoid repeated lookups
    private volatile String collectionId;

    @PostConstruct
    void init() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.baseUrl = config.chroma().baseUrl();
        this.collectionName = config.chroma().collection();
        LOG.infof("ChromaDBClient initialized — base: %s collection: %s",
                baseUrl, collectionName);
    }

    // ----------------------------------------------------------------
    // Collection management
    // ----------------------------------------------------------------

    /**
     * Ensures the collection exists, creates it if not.
     * Returns the collection ID.
     */
    public String ensureCollection() throws ChromaException {
        if (collectionId != null) {
            return collectionId;
        }

        // Try to get existing collection
        try {
            String id = getCollectionId();
            this.collectionId = id;
            LOG.infof("Using existing ChromaDB collection '%s' (id=%s)", collectionName, id);
            return id;
        } catch (ChromaException e) {
            // Collection doesn't exist — create it
            LOG.infof("Collection '%s' not found, creating...", collectionName);
            return createCollection();
        }
    }

    private String getCollectionId() throws ChromaException {
        String url = baseUrl + "/api/v2/collections/" + collectionName;
        String response = get(url);
        return extractJsonField(response, "id");
    }

    private String createCollection() throws ChromaException {
        String url = baseUrl + "/api/v2/collections";
        String body = String.format(
                "{\"name\":\"%s\",\"metadata\":{\"description\":\"Kafka diagnostic knowledge base\"}}",
                collectionName);
        String response = post(url, body);
        String id = extractJsonField(response, "id");
        this.collectionId = id;
        LOG.infof("Created ChromaDB collection '%s' (id=%s)", collectionName, id);
        return id;
    }

    // ----------------------------------------------------------------
    // Document operations
    // ----------------------------------------------------------------

    /**
     * Insert a batch of chunks into the collection.
     *
     * @param chunks list of chunks to insert
     */
    public void insertChunks(List<DocumentChunk> chunks) throws ChromaException {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }

        String colId = ensureCollection();
        String url = baseUrl + "/api/v2/collections/" + colId + "/upsert";

        // Build the upsert request body
        StringBuilder ids        = new StringBuilder("[");
        StringBuilder embeddings = new StringBuilder("[");
        StringBuilder documents  = new StringBuilder("[");
        StringBuilder metadatas  = new StringBuilder("[");

        for (int i = 0; i < chunks.size(); i++) {
            DocumentChunk c = chunks.get(i);
            if (i > 0) {
                ids.append(",");
                embeddings.append(",");
                documents.append(",");
                metadatas.append(",");
            }
            ids.append("\"").append(escapeJson(c.id())).append("\"");
            embeddings.append(floatArrayToJson(c.embedding()));
            documents.append("\"").append(escapeJson(c.text())).append("\"");
            metadatas.append(buildMetadataJson(c));
        }

        ids.append("]");
        embeddings.append("]");
        documents.append("]");
        metadatas.append("]");

        String body = String.format(
                "{\"ids\":%s,\"embeddings\":%s,\"documents\":%s,\"metadatas\":%s}",
                ids, embeddings, documents, metadatas);

        post(url, body);
        LOG.debugf("Inserted %d chunks into collection '%s'", chunks.size(), collectionName);
    }

    /**
     * Save the SHA-256 hash of an indexed file.
     * Stored as a special document with id="__sha256::<filename>".
     */
    public void saveFileHash(String filename, String sha256) throws ChromaException {
        String colId = ensureCollection();
        String url = baseUrl + "/api/v2/collections/" + colId + "/upsert";

        String id = "__sha256::" + filename;
        String body = String.format(
                "{\"ids\":[\"%s\"],\"documents\":[\"%s\"],\"metadatas\":[{\"type\":\"sha256\"}]}",
                escapeJson(id), sha256);

        post(url, body);
    }

    /**
     * Retrieve the stored SHA-256 hash for a filename.
     * Returns null if not found.
     */
    public String getFileHash(String filename) throws ChromaException {
        String colId = ensureCollection();
        String url = baseUrl + "/api/v2/collections/" + colId + "/get";

        String id = "__sha256::" + filename;
        String body = String.format("{\"ids\":[\"%s\"]}", escapeJson(id));

        String response = post(url, body);

        // Parse documents array from response
        int docsIdx = response.indexOf("\"documents\":[");
        if (docsIdx == -1) return null;

        int start = response.indexOf('"', docsIdx + 13);
        if (start == -1) return null;
        int end = response.indexOf('"', start + 1);
        if (end == -1) return null;

        String hash = response.substring(start + 1, end);
        return hash.isEmpty() ? null : hash;
    }

    // ----------------------------------------------------------------
    // Similarity search
    // ----------------------------------------------------------------

    /**
     * Search for the most similar chunks to the given query vector.
     *
     * @param queryVector the embedding of the query text
     * @param topK        number of results to return
     * @return list of search results with text and metadata
     */
    public List<SearchResult> query(float[] queryVector, int topK) throws ChromaException {
        String colId = ensureCollection();
        String url = baseUrl + "/api/v2/collections/" + colId + "/query";

        String body = String.format(
                "{\"query_embeddings\":[%s],\"n_results\":%d,\"include\":[\"documents\",\"metadatas\",\"distances\"]}",
                floatArrayToJson(queryVector), topK);

        String response = post(url, body);
        return parseQueryResults(response);
    }

    // ----------------------------------------------------------------
    // Result parsing
    // ----------------------------------------------------------------

    private List<SearchResult> parseQueryResults(String json) {
        List<SearchResult> results = new ArrayList<>();

        try {
            // Extract documents array
            int docsStart = json.indexOf("\"documents\":[[");
            if (docsStart == -1) return results;

            int innerStart = json.indexOf('[', docsStart + 13);
            int innerEnd   = findMatchingBracket(json, innerStart);
            if (innerEnd == -1) return results;

            List<String> docs = extractJsonStringArray(json.substring(innerStart, innerEnd + 1));

            // Extract metadatas array
            int metaStart = json.indexOf("\"metadatas\":[[");
            List<String> metas = new ArrayList<>();
            if (metaStart != -1) {
                int mInner = json.indexOf('[', metaStart + 13);
                int mEnd   = findMatchingBracket(json, mInner);
                if (mEnd != -1) {
                    metas = extractJsonObjectArray(json.substring(mInner, mEnd + 1));
                }
            }

            for (int i = 0; i < docs.size(); i++) {
                String meta = i < metas.size() ? metas.get(i) : "{}";
                results.add(new SearchResult(docs.get(i), meta));
            }

        } catch (Exception e) {
            LOG.warnf("Failed to parse ChromaDB query results: %s", e.getMessage());
        }

        return results;
    }

    // ----------------------------------------------------------------
    // HTTP helpers
    // ----------------------------------------------------------------

    private String get(String url) throws ChromaException {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .timeout(Duration.ofSeconds(15))
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 404) {
                throw new ChromaException("Not found: " + url);
            }
            if (response.statusCode() >= 400) {
                throw new ChromaException("HTTP " + response.statusCode() + ": " + response.body());
            }

            return response.body();

        } catch (ChromaException e) {
            throw e;
        } catch (Exception e) {
            throw new ChromaException("GET failed for " + url + ": " + e.getMessage(), e);
        }
    }

    private String post(String url, String body) throws ChromaException {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(30))
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 400) {
                throw new ChromaException(
                        "POST " + url + " returned HTTP " + response.statusCode() +
                        ": " + response.body());
            }

            return response.body();

        } catch (ChromaException e) {
            throw e;
        } catch (Exception e) {
            throw new ChromaException("POST failed for " + url + ": " + e.getMessage(), e);
        }
    }

    // ----------------------------------------------------------------
    // JSON helpers
    // ----------------------------------------------------------------

    private String extractJsonField(String json, String field) throws ChromaException {
        String key = "\"" + field + "\":\"";
        int start = json.indexOf(key);
        if (start == -1) {
            throw new ChromaException("Field '" + field + "' not found in: " + json);
        }
        int valueStart = start + key.length();
        int valueEnd   = json.indexOf('"', valueStart);
        if (valueEnd == -1) {
            throw new ChromaException("Could not parse field '" + field + "' from: " + json);
        }
        return json.substring(valueStart, valueEnd);
    }

    private String floatArrayToJson(float[] arr) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(arr[i]);
        }
        sb.append("]");
        return sb.toString();
    }

    private String buildMetadataJson(DocumentChunk chunk) {
        return String.format(
                "{\"version\":\"%s\",\"filename\":\"%s\",\"page\":%d,\"sha256\":\"%s\"}",
                escapeJson(chunk.version()),
                escapeJson(chunk.filename()),
                chunk.page(),
                chunk.sha256());
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private List<String> extractJsonStringArray(String json) {
        List<String> results = new ArrayList<>();
        int i = 0;
        while (i < json.length()) {
            int start = json.indexOf('"', i);
            if (start == -1) break;
            int end = start + 1;
            while (end < json.length()) {
                if (json.charAt(end) == '"' && json.charAt(end - 1) != '\\') break;
                end++;
            }
            if (end < json.length()) {
                results.add(json.substring(start + 1, end));
            }
            i = end + 1;
        }
        return results;
    }

    private List<String> extractJsonObjectArray(String json) {
        List<String> results = new ArrayList<>();
        int i = 0;
        while (i < json.length()) {
            int start = json.indexOf('{', i);
            if (start == -1) break;
            int end = findMatchingBrace(json, start);
            if (end == -1) break;
            results.add(json.substring(start, end + 1));
            i = end + 1;
        }
        return results;
    }

    private int findMatchingBracket(String json, int openPos) {
        int depth = 0;
        for (int i = openPos; i < json.length(); i++) {
            if (json.charAt(i) == '[') depth++;
            else if (json.charAt(i) == ']') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    private int findMatchingBrace(String json, int openPos) {
        int depth = 0;
        for (int i = openPos; i < json.length(); i++) {
            if (json.charAt(i) == '{') depth++;
            else if (json.charAt(i) == '}') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    // ----------------------------------------------------------------
    // Value objects
    // ----------------------------------------------------------------

    /**
     * A chunk of text ready to be inserted into ChromaDB.
     */
    public record DocumentChunk(
            String id,
            String text,
            float[] embedding,
            String version,
            String filename,
            int page,
            String sha256
    ) {}

    /**
     * A result from a ChromaDB similarity search.
     */
    public record SearchResult(String text, String metadata) {}

    /**
     * ChromaDB operation exception.
     */
    public static class ChromaException extends Exception {
        public ChromaException(String message) {
            super(message);
        }
        public ChromaException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}