package com.redhat.kafka.diag.resource;

import com.redhat.kafka.diag.agent.KafkaDiagnosticAgent;
import com.redhat.kafka.diag.config.AgentConfig;
import com.redhat.kafka.diag.tools.KCSSearchTool;
import com.redhat.kafka.diag.tools.RAGQueryTool;
import com.redhat.kafka.diag.tools.ReportUploadTool;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * REST endpoint for the Kafka Diagnostic Agent.
 *
 * POST /api/diagnose         — diagnose using live cluster access
 * POST /api/diagnose-report  — diagnose using an uploaded Strimzi report.sh ZIP
 * GET  /api/health           — health check
 *
 * In report mode, ALL relevant content is pre-extracted from the ZIP and
 * injected directly into the prompt. The agent does not need to call
 * analyzeUploadedReport tools — it receives the full context upfront.
 * This avoids the model hallucinating "no files found" for existing resources.
 */
@Path("/api")
public class DiagnosticResource {

    private static final Logger LOG = Logger.getLogger(DiagnosticResource.class);

    private static final int MAX_FILE_CHARS = 50_000;
    private static final int MAX_FILES = 200;
    // Max chars per section injected into prompt — keeps total context manageable
    private static final int MAX_SECTION_CHARS = 1500;

    private static final java.util.Set<String> TEXT_EXTENSIONS = java.util.Set.of(
            ".yaml", ".yml", ".json", ".txt", ".log", ".properties", ".conf"
    );

    @Inject KafkaDiagnosticAgent agent;
    @Inject AgentConfig config;
    @Inject RAGQueryTool ragQueryTool;
    @Inject KCSSearchTool kcsSearchTool;

    // ----------------------------------------------------------------
    // POST /api/diagnose — live cluster mode
    // ----------------------------------------------------------------

    @POST
    @Path("/diagnose")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response diagnose(DiagnoseRequest request) {
        if (request == null || request.question() == null || request.question().isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ErrorResponse("question is required"))
                    .build();
        }

        String namespace = resolveNamespace(request.namespace());
        LOG.infof("Diagnosing (live): namespace=%s question=%s", namespace, request.question());

        String ragContext = prefetchRAG(request.question());
        String kcsContext = prefetchKCS(request.question());

        String question = String.format(
                "[namespace: %s] %s" +
                " — MANDATORY: call getKafkaClusters, getKafkaEvents and getPods to get cluster state." +
                " Then use the following pre-fetched context to enrich your response:\n\n" +
                "=== PRE-FETCHED DOCUMENTATION ===\n%s\n\n" +
                "=== PRE-FETCHED KCS ARTICLES ===\n%s\n\n" +
                "Do not invent documentation links or KCS articles beyond what is provided above.",
                namespace, request.question(), ragContext, kcsContext);

        try {
            String answer = stripThinkBlocks(agent.diagnose(question));
            return Response.ok(new DiagnoseResponse(answer, namespace, false)).build();
        } catch (Exception e) {
            LOG.errorf(e, "Error during diagnosis");
            return Response.serverError()
                    .entity(new ErrorResponse("Diagnosis failed: " + e.getMessage()))
                    .build();
        }
    }

    // ----------------------------------------------------------------
    // POST /api/diagnose-report — report-based diagnosis
    // ----------------------------------------------------------------

    @POST
    @Path("/diagnose-report")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.APPLICATION_JSON)
    public Response diagnoseReport(
            @RestForm("zip") FileUpload zipFile,
            @RestForm("question") String question) {

        if (question == null || question.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ErrorResponse("question is required"))
                    .build();
        }
        if (zipFile == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ErrorResponse("zip file is required"))
                    .build();
        }

        try {
            // Step 1: extract ZIP contents
            Map<String, String> files = extractZipContents(
                    java.nio.file.Files.newInputStream(zipFile.uploadedFile()));

            if (files.isEmpty()) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(new ErrorResponse("No readable files found in the ZIP"))
                        .build();
            }

            ReportUploadTool.setReportFiles(files);
            LOG.infof("Diagnosing from ZIP: %d files, question=%s", files.size(), question);

            // Step 2: pre-extract all relevant sections from the ZIP
            String kafkaSection      = truncate(extractByPrefix(files, "kafkas/"), MAX_SECTION_CHARS);
            String podsSection       = truncate(extractByPrefix(files, "pods/"), MAX_SECTION_CHARS);
            String eventsSection     = truncate(extractByPrefix(files, "events/"), MAX_SECTION_CHARS);
            String connectSection    = truncate(extractByPrefix(files, "kafkaconnects/"), MAX_SECTION_CHARS);
            String connectorSection  = truncate(extractByPrefix(files, "kafkaconnectors/"), MAX_SECTION_CHARS);
            String nodepoolSection   = truncate(extractByPrefix(files, "kafkanodepools/"), MAX_SECTION_CHARS);
            String topicsSection     = truncate(extractByPrefix(files, "kafkatopics/"), MAX_SECTION_CHARS);
            String logsSection       = truncate(extractByPrefix(files, "logs/"), MAX_SECTION_CHARS);

            // Step 3: extract issue from kafka or connector YAML for targeted RAG/KCS query
            String issueQuery = extractIssue(kafkaSection,
                                extractIssue(connectorSection,
                                extractIssue(connectSection, question)));
            LOG.infof("Using issue query for RAG/KCS: %s", issueQuery);

            // Step 4: pre-fetch RAG and KCS
            String ragContext = prefetchRAG(issueQuery);
            String kcsContext = prefetchKCS(issueQuery);

            // Step 5: build enriched prompt with all pre-extracted content
            StringBuilder reportContent = new StringBuilder();
            appendSection(reportContent, "KAFKA CLUSTER", kafkaSection);
            appendSection(reportContent, "KAFKA NODE POOLS", nodepoolSection);
            appendSection(reportContent, "PODS", podsSection);
            appendSection(reportContent, "EVENTS", eventsSection);
            appendSection(reportContent, "KAFKA CONNECT", connectSection);
            appendSection(reportContent, "KAFKA CONNECTORS", connectorSection);
            appendSection(reportContent, "KAFKA TOPICS", topicsSection);
            appendSection(reportContent, "LOGS", logsSection);

            String q = "[report-mode: true] " + question + "\n\n" +
                    "The following content was extracted directly from the uploaded ZIP report.\n" +
                    "Use it to answer the question. Do NOT say files are missing — if a section\n" +
                    "is empty below, that resource does not exist in the report.\n\n" +
                    "=== REPORT CONTENT ===\n" + reportContent + "\n\n" +
                    "=== PRE-FETCHED DOCUMENTATION ===\n" + ragContext + "\n\n" +
                    "=== PRE-FETCHED KCS ARTICLES ===\n" + kcsContext + "\n\n" +
                    "Do not use KubernetesTool." +
                    " Do not invent documentation links or KCS articles beyond what is provided above.";

            String answer = stripThinkBlocks(agent.diagnose(q));
            return Response.ok(new DiagnoseResponse(answer, "from-report", true)).build();

        } catch (Exception e) {
            LOG.errorf(e, "Error during report diagnosis");
            return Response.serverError()
                    .entity(new ErrorResponse("Diagnosis failed: " + e.getMessage()))
                    .build();
        } finally {
            ReportUploadTool.clearReportFiles();
        }
    }

    // ----------------------------------------------------------------
    // GET /api/health
    // ----------------------------------------------------------------

    @GET
    @Path("/health")
    @Produces(MediaType.APPLICATION_JSON)
    public Response health() {
        return Response.ok("{\"status\":\"ok\",\"agent\":\"kafka-diag-agent\"}").build();
    }

    // ----------------------------------------------------------------
    // Pre-fetch helpers
    // ----------------------------------------------------------------

    private String prefetchRAG(String query) {
        try {
            String result = ragQueryTool.queryDocumentation(query);
            LOG.infof("Pre-fetched RAG context for: %s", query);
            return result;
        } catch (Exception e) {
            LOG.warnf("RAG pre-fetch failed: %s", e.getMessage());
            return "[RAG context unavailable]";
        }
    }

    private String prefetchKCS(String query) {
        try {
            String result = kcsSearchTool.searchKCS(query);
            LOG.infof("Pre-fetched KCS context for: %s", query);
            return result;
        } catch (Exception e) {
            LOG.warnf("KCS pre-fetch failed: %s", e.getMessage());
            return "[KCS context unavailable]";
        }
    }

    private void appendSection(StringBuilder sb, String title, String content) {
        sb.append("--- ").append(title).append(" ---\n");
        if (content == null || content.isBlank()) {
            sb.append("(not present in report)\n");
        } else {
            sb.append(content);
        }
        sb.append("\n");
    }

    // ----------------------------------------------------------------
    // Issue extraction
    // ----------------------------------------------------------------

    /**
     * Extract the most relevant issue from YAML content.
     * Looks for conditions.message, conditions.reason, or NotReady type.
     * Falls back to the provided fallback string if nothing specific is found.
     */
    private String extractIssue(String content, String fallback) {
        if (content == null || content.isBlank()) return fallback;

        String[] lines = content.split("\n");

        // Pass 1 — look for message: field and collect multiline value
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (trimmed.startsWith("message:")) {
                StringBuilder msg = new StringBuilder();
                String sameLine = trimmed.substring(8).trim()
                        .replace("'", "").replace("\"", "").trim();
                msg.append(sameLine);

                int currentIndent = lines[i].indexOf("message:");
                for (int j = i + 1; j < lines.length && j < i + 5; j++) {
                    String next = lines[j];
                    if (next.isBlank()) break;
                    int nextIndent = next.length() - next.stripLeading().length();
                    if (nextIndent > currentIndent) {
                        msg.append(" ").append(next.trim()
                                .replace("'", "").replace("\"", ""));
                    } else {
                        break;
                    }
                }

                String result = msg.toString().trim();
                if (result.length() > 20 && result.contains(" ")) {
                    if (result.length() > 200) result = result.substring(0, 200);
                    LOG.infof("Extracted issue message: %s", result);
                    return result;
                }
            }
        }

        // Pass 2 — fallback to reason field
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("reason:")) {
                String reason = trimmed.substring(7).trim();
                if (!reason.isBlank() && !reason.equals("null") && !reason.equals("None")) {
                    LOG.infof("Extracted issue reason: %s", reason);
                    return reason;
                }
            }
        }

        return fallback;
    }

    // ----------------------------------------------------------------
    // Content helpers
    // ----------------------------------------------------------------

    private String extractByPrefix(Map<String, String> files, String prefix) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : files.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                sb.append("# ").append(entry.getKey()).append("\n");
                sb.append(entry.getValue()).append("\n\n");
            }
        }
        return sb.toString();
    }

    private String truncate(String content, int maxChars) {
        if (content == null || content.length() <= maxChars) return content;
        return content.substring(0, maxChars) + "\n[... truncated]";
    }

    // ----------------------------------------------------------------
    // ZIP extraction
    // ----------------------------------------------------------------

    private Map<String, String> extractZipContents(InputStream is) throws Exception {
        Map<String, String> files = new LinkedHashMap<>();

        try (ZipInputStream zis = new ZipInputStream(is)) {
            ZipEntry entry;
            int fileCount = 0;

            while ((entry = zis.getNextEntry()) != null && fileCount < MAX_FILES) {
                String name = entry.getName();

                if (entry.isDirectory()) { zis.closeEntry(); continue; }
                if (!isTextFile(name))   { zis.closeEntry(); continue; }

                byte[] buffer = new byte[8192];
                StringBuilder sb = new StringBuilder();
                int read, totalRead = 0;

                while ((read = zis.read(buffer)) != -1) {
                    totalRead += read;
                    if (totalRead <= MAX_FILE_CHARS) {
                        sb.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
                    }
                }

                if (totalRead > MAX_FILE_CHARS) {
                    sb.append("\n[File truncated at ").append(MAX_FILE_CHARS).append(" chars]");
                }

                String normalizedPath = normalizePath(name);
                if (!sb.toString().isBlank()) {
                    files.put(normalizedPath, sb.toString());
                    fileCount++;
                }

                zis.closeEntry();
            }
        }

        return files;
    }

    private String normalizePath(String path) {
        path = path.replace('\\', '/');
        int reportsIdx = path.indexOf("/reports/");
        if (reportsIdx >= 0) {
            return path.substring(reportsIdx + "/reports/".length());
        }
        if (path.startsWith("reports/")) {
            return path.substring("reports/".length());
        }
        return path;
    }

    private boolean isTextFile(String name) {
        String lower = name.toLowerCase();
        for (String ext : TEXT_EXTENSIONS) {
            if (lower.endsWith(ext)) return true;
        }
        return false;
    }

    // ----------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------

    private String resolveNamespace(String requested) {
        return (requested != null && !requested.isBlank())
                ? requested
                : config.defaultNamespace();
    }

    private String stripThinkBlocks(String text) {
        if (text == null) return "";
        return text.replaceAll("(?s)<think>.*?</think>", "").trim();
    }

    // ----------------------------------------------------------------
    // Request / Response records
    // ----------------------------------------------------------------

    public record DiagnoseRequest(String question, String namespace) {}
    public record DiagnoseResponse(String answer, String namespace, boolean fromReport) {}
    public record UploadResponse(String message, int filesExtracted, String filename) {}
    public record ErrorResponse(String error) {}
}