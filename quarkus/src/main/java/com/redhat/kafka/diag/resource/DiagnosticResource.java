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
 * Both modes pre-fetch RAG documentation and KCS articles before invoking
 * the agent, so the model receives enriched context regardless of whether
 * it decides to call queryDocumentation and searchKCS itself.
 */
@Path("/api")
public class DiagnosticResource {

    private static final Logger LOG = Logger.getLogger(DiagnosticResource.class);

    private static final int MAX_FILE_CHARS = 50_000;
    private static final int MAX_FILES = 200;
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

        // Pre-fetch RAG and KCS context based on the user question
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
            Map<String, String> files = extractZipContents(
                    java.nio.file.Files.newInputStream(zipFile.uploadedFile()));

            if (files.isEmpty()) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(new ErrorResponse("No readable files found in the ZIP"))
                        .build();
            }

            ReportUploadTool.setReportFiles(files);
            LOG.infof("Diagnosing from ZIP: %d files, question=%s", files.size(), question);

            // Extract issue from kafka YAML to build a specific RAG/KCS query
            String kafkaContent = extractByPrefix(files, "kafkas/");
            String issueQuery   = extractIssue(kafkaContent, question);

            // Pre-fetch RAG and KCS before calling the agent
            String ragContext = prefetchRAG(issueQuery);
            String kcsContext = prefetchKCS(issueQuery);

            String q = "[report-mode: true] " + question +
                    " — MANDATORY: call analyzeUploadedReport for summary, kafka, pods and events." +
                    " Then use the following pre-fetched context to enrich your response:\n\n" +
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

    /**
     * Pre-fetch RAG documentation context for a given query.
     * Called before invoking the agent so context is always available.
     */
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

    /**
     * Pre-fetch KCS articles for a given query.
     * Called before invoking the agent so articles are always available.
     */
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

    /**
     * Extract the most relevant issue description from the kafka YAML content.
     * Looks for condition messages and reason fields that describe the problem.
     * Falls back to the original question if nothing specific is found.
     */
    private String extractIssue(String kafkaContent, String fallback) {
        if (kafkaContent == null || kafkaContent.isBlank()) return fallback;

        // Look for reason field in conditions
        int reasonIdx = kafkaContent.indexOf("reason:");
        if (reasonIdx >= 0) {
            int start = reasonIdx + 7;
            int end = kafkaContent.indexOf('\n', start);
            if (end > start) {
                String reason = kafkaContent.substring(start, end).trim();
                if (!reason.isBlank()) {
                    LOG.infof("Extracted issue from kafka YAML: %s", reason);
                    return reason;
                }
            }
        }

        // Look for message field in conditions
        int msgIdx = kafkaContent.indexOf("message:");
        if (msgIdx >= 0) {
            int start = msgIdx + 8;
            int end = kafkaContent.indexOf('\n', start);
            if (end > start) {
                String msg = kafkaContent.substring(start, end).trim();
                if (!msg.isBlank() && msg.length() > 10) {
                    LOG.infof("Extracted issue message from kafka YAML: %s", msg);
                    return msg;
                }
            }
        }

        return fallback;
    }

    /**
     * Extract content for files matching a directory prefix.
     */
    private String extractByPrefix(Map<String, String> files, String prefix) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : files.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                sb.append(entry.getValue()).append("\n");
            }
        }
        return sb.toString();
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
            path = path.substring(reportsIdx + "/reports/".length());
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