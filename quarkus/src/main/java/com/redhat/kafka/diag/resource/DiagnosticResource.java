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
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * REST endpoint for the Kafka Diagnostic Agent.
 *
 * POST /api/diagnose         — diagnose using live cluster access
 * POST /api/diagnose-report  — diagnose using an uploaded Strimzi report.sh ZIP
 * GET  /api/health           — health check
 *
 * Both modes use a TWO-PHASE approach:
 *
 * Phase 1 — agent identifies the specific issue and returns JSON:
 *   {"issue": "specific error found", "component": "kafka|connect|..."}
 *
 * Phase 2 — Java uses the detected issue to search RAG + KCS, then agent
 *   produces the full structured diagnosis enriched with relevant docs and solutions.
 *
 * Each phase uses a different UUID as @MemoryId, so LangChain4j gives each
 * call a completely isolated memory — Phase 1 JSON never contaminates Phase 2.
 */
@Path("/api")
public class DiagnosticResource {

    private static final Logger LOG = Logger.getLogger(DiagnosticResource.class);

    private static final int MAX_FILE_CHARS    = 50_000;
    private static final int MAX_FILES         = 200;
    private static final int MAX_SECTION_CHARS = 1500;

    private static final java.util.Set<String> TEXT_EXTENSIONS = java.util.Set.of(
            ".yaml", ".yml", ".json", ".txt", ".log", ".properties", ".conf"
    );

    @Inject KafkaDiagnosticAgent agent;
    @Inject AgentConfig config;
    @Inject RAGQueryTool ragQueryTool;
    @Inject KCSSearchTool kcsSearchTool;

    // ----------------------------------------------------------------
    // POST /api/diagnose — live cluster mode (two-phase)
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

        try {
            // Phase 1: agent inspects cluster and identifies the specific issue
            String phase1Prompt = String.format(
                    "[namespace: %s] %s\n\n" +
                    "MANDATORY: call getKafkaClusters, getKafkaEvents and getPods to inspect the cluster.\n" +
                    "Then return ONLY a JSON object — no other text — with this exact format:\n" +
                    "{\"issue\": \"<specific technical issue found>\", " +
                    "\"component\": \"<kafka|connect|debezium|topic|nodepool|pod|mirrormaker>\"}\n" +
                    "If no issue found: {\"issue\": \"cluster healthy\", \"component\": \"kafka\"}",
                    namespace, request.question());

            String p1id = UUID.randomUUID().toString();
            String phase1Raw = stripThinkBlocks(agent.diagnose(p1id, phase1Prompt));
            LOG.infof("Phase 1 raw: %s", phase1Raw.substring(0, Math.min(300, phase1Raw.length())));
            String detectedIssue = parseIssueFromJson(phase1Raw, request.question());
            LOG.infof("Phase 1 detected issue: %s", detectedIssue);

            // Phase 2: search RAG and KCS with the actual issue found
            String ragContext = prefetchRAG(detectedIssue);
            String kcsContext = prefetchKCS(detectedIssue);

            // Phase 2: full diagnosis — fresh memory (different UUID)
            String phase2Prompt = String.format(
                    "[namespace: %s] %s\n\n" +
                    "MANDATORY: call getKafkaClusters, getKafkaEvents and getPods to get cluster state.\n\n" +
                    "=== PRE-FETCHED DOCUMENTATION ===\n%s\n\n" +
                    "=== PRE-FETCHED KCS ARTICLES ===\n%s\n\n" +
                    "Do not invent documentation links or KCS articles beyond what is provided above.",
                    namespace, request.question(), ragContext, kcsContext);

            String p2id = UUID.randomUUID().toString();
            String answer = stripThinkBlocks(agent.diagnose(p2id, phase2Prompt));
            return Response.ok(new DiagnoseResponse(answer, namespace, false)).build();

        } catch (Exception e) {
            LOG.errorf(e, "Error during diagnosis");
            return Response.serverError()
                    .entity(new ErrorResponse("Diagnosis failed: " + e.getMessage()))
                    .build();
        }
    }

    // ----------------------------------------------------------------
    // POST /api/diagnose-report — report-based diagnosis (two-phase)
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
            String kafkaSection     = truncate(extractByPrefix(files, "kafkas/"), MAX_SECTION_CHARS);
            String podsSection      = truncate(extractByPrefix(files, "pods/"), MAX_SECTION_CHARS);
            String eventsSection    = truncate(extractByPrefix(files, "events/"), MAX_SECTION_CHARS);
            String connectSection   = truncate(extractByPrefix(files, "kafkaconnects/"), MAX_SECTION_CHARS);
            String connectorSection = truncate(extractByPrefix(files, "kafkaconnectors/"), MAX_SECTION_CHARS);
            String nodepoolSection  = truncate(extractByPrefix(files, "kafkanodepools/"), MAX_SECTION_CHARS);
            String topicsSection    = truncate(extractByPrefix(files, "kafkatopics/"), MAX_SECTION_CHARS);
            String logsSection      = truncate(extractByPrefix(files, "logs/"), MAX_SECTION_CHARS);

            // Step 3: build report content string
            StringBuilder reportContent = new StringBuilder();
            appendSection(reportContent, "KAFKA CLUSTER",    kafkaSection);
            appendSection(reportContent, "KAFKA NODE POOLS", nodepoolSection);
            appendSection(reportContent, "PODS",             podsSection);
            appendSection(reportContent, "EVENTS",           eventsSection);
            appendSection(reportContent, "KAFKA CONNECT",    connectSection);
            appendSection(reportContent, "KAFKA CONNECTORS", connectorSection);
            appendSection(reportContent, "KAFKA TOPICS",     topicsSection);
            appendSection(reportContent, "LOGS",             logsSection);
            String reportStr = reportContent.toString();

            // Phase 1: agent reads the report and identifies the specific issue
            String phase1Prompt = "Analyze the following Kafka cluster report and identify the main issue.\n" +
                    "Return ONLY a JSON object — no other text — with this exact format:\n" +
                    "{\"issue\": \"<specific technical issue found>\", " +
                    "\"component\": \"<kafka|connect|debezium|topic|nodepool|pod|mirrormaker>\"}\n" +
                    "If no issue found: {\"issue\": \"cluster healthy\", \"component\": \"kafka\"}\n\n" +
                    "=== REPORT CONTENT ===\n" + reportStr;

            String p1id = UUID.randomUUID().toString();
            String phase1Raw = stripThinkBlocks(agent.diagnose(p1id, phase1Prompt));
            LOG.infof("Phase 1 raw: %s", phase1Raw.substring(0, Math.min(300, phase1Raw.length())));
            String detectedIssue = parseIssueFromJson(phase1Raw, question);
            LOG.infof("Phase 1 detected issue: %s", detectedIssue);

            // Phase 2: search RAG and KCS with the actual issue found
            String ragContext = prefetchRAG(detectedIssue);
            String kcsContext = prefetchKCS(detectedIssue);

            // Phase 2: full diagnosis — fresh memory (different UUID)
            String q = "[report-mode: true] " + question + "\n\n" +
                    "The following content was extracted from the uploaded ZIP report.\n" +
                    "Do NOT say files are missing — if a section shows '(not present in report)'\n" +
                    "that resource genuinely does not exist in the report.\n\n" +
                    "=== REPORT CONTENT ===\n" + reportStr + "\n\n" +
                    "=== PRE-FETCHED DOCUMENTATION ===\n" + ragContext + "\n\n" +
                    "=== PRE-FETCHED KCS ARTICLES ===\n" + kcsContext + "\n\n" +
                    "Do not use KubernetesTool." +
                    " Do not invent documentation links or KCS articles beyond what is provided above.";

            String p2id = UUID.randomUUID().toString();
            String answer = stripThinkBlocks(agent.diagnose(p2id, q));
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
    // Phase 1 — JSON parsing
    // ----------------------------------------------------------------

    private String parseIssueFromJson(String raw, String fallback) {
        if (raw == null || raw.isBlank()) return fallback;

        String cleaned = raw.replaceAll("```json", "").replaceAll("```", "").trim();

        int issueIdx = cleaned.indexOf("\"issue\"");
        if (issueIdx == -1) issueIdx = cleaned.indexOf("'issue'");
        if (issueIdx == -1) return fallback;

        int colonIdx = cleaned.indexOf(":", issueIdx);
        if (colonIdx == -1) return fallback;

        int startQuote = cleaned.indexOf("\"", colonIdx);
        if (startQuote == -1) startQuote = cleaned.indexOf("'", colonIdx);
        if (startQuote == -1) return fallback;

        char quoteChar = cleaned.charAt(startQuote);
        int endQuote = cleaned.indexOf(quoteChar, startQuote + 1);
        if (endQuote == -1) return fallback;

        String issue = cleaned.substring(startQuote + 1, endQuote).trim();

        if (issue.isBlank() || issue.equalsIgnoreCase("cluster healthy")) {
            LOG.infof("No specific issue detected — using fallback: %s", fallback);
            return fallback;
        }

        return issue;
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