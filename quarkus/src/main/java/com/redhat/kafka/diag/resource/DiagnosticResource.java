package com.redhat.kafka.diag.resource;

import com.redhat.kafka.diag.agent.KafkaDiagnosticAgent;
import com.redhat.kafka.diag.config.AgentConfig;
import com.redhat.kafka.diag.tools.ReportUploadTool;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;
import io.smallrye.common.annotation.Blocking;

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
 * POST /api/upload-report    — upload a Strimzi report.sh ZIP file
 * POST /api/diagnose-report  — diagnose using the uploaded ZIP report
 * GET  /api/health           — health check
 */
@Path("/api")
public class DiagnosticResource {

    private static final Logger LOG = Logger.getLogger(DiagnosticResource.class);

    // Maximum uncompressed content per file — 50KB
    private static final int MAX_FILE_CHARS = 50_000;
    // Maximum total files to extract from the ZIP
    private static final int MAX_FILES = 200;
    // File extensions to extract as text
    private static final java.util.Set<String> TEXT_EXTENSIONS = java.util.Set.of(
            ".yaml", ".yml", ".json", ".txt", ".log", ".properties", ".conf"
    );

    @Inject
    KafkaDiagnosticAgent agent;

    @Inject
    AgentConfig config;

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
        String question = String.format("[namespace: %s] %s" +
           " — MANDATORY: call KubernetesTool to get cluster state, pods and events." +
           " Then MANDATORY: call queryDocumentation with the issue found." +
           " Then MANDATORY: call searchKCS with the issue found." +
           " Do not invent documentation links or KCS articles.",
           namespace, request.question());

        LOG.infof("Diagnosing (live): namespace=%s question=%s", namespace, request.question());

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
    // POST /api/upload-report — accept ZIP from multipart form
    // ----------------------------------------------------------------

    /**
     * Accept a Strimzi report.sh ZIP file uploaded via multipart/form-data.
     * Extracts all text/YAML files from the ZIP into a Map and stores them
     * in the ReportUploadTool ThreadLocal for the subsequent diagnose-report call.
     *
     * Form field name: "report"
     */
    @POST
    @Path("/upload-report")
    @Consumes(MediaType.APPLICATION_OCTET_STREAM)
    @Produces(MediaType.APPLICATION_JSON)
    public Response uploadReport(byte[] body) {
        if (body == null || body.length == 0) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ErrorResponse("No file content received"))
                    .build();
        }

        LOG.infof("Received report upload: %d bytes", body.length);

        try {
            Map<String, String> files = extractZipContents(
                    new java.io.ByteArrayInputStream(body));

            if (files.isEmpty()) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(new ErrorResponse(
                                "No readable files found in the ZIP. " +
                                "Make sure it is a valid Strimzi report.sh output."))
                        .build();
            }

            ReportUploadTool.setReportFiles(files);
            LOG.infof("Report ZIP extracted — %d files ready for analysis", files.size());

            return Response.ok(new UploadResponse(
                    "Report uploaded successfully — " + files.size() + " files extracted",
                    files.size(),
                    "report.zip"
            )).build();

        } catch (Exception e) {
            LOG.errorf(e, "Failed to process uploaded ZIP");
            return Response.serverError()
                    .entity(new ErrorResponse("Failed to process ZIP: " + e.getMessage()))
                    .build();
        }
    }

    // ----------------------------------------------------------------
    // POST /api/diagnose-report — report-based diagnosis
    // ----------------------------------------------------------------

    /**
     * Diagnose using a previously uploaded and extracted ZIP report.
     * The agent will call ReportUploadTool to access the file contents.
     * The report is cleared after the response is sent.
     */
    @POST
    @Path("/diagnose-report")
    @Blocking
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

            String q = "[report-mode: true] " + question +
           " — MANDATORY: call analyzeUploadedReport for summary, kafka, pods and events." +
           " Then MANDATORY: call queryDocumentation with the issue found." +
           " Then MANDATORY: call searchKCS with the issue found." +
           " Do not use KubernetesTool. Do not invent documentation links or KCS articles.";

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
    // ZIP extraction
    // ----------------------------------------------------------------

    /**
     * Extract all text files from a ZIP stream into a map of path → content.
     * Skips binary files, directories, secrets content, and oversized files.
     */
    private Map<String, String> extractZipContents(InputStream is) throws Exception {
        Map<String, String> files = new LinkedHashMap<>();

        try (ZipInputStream zis = new ZipInputStream(is)) {
            ZipEntry entry;
            int fileCount = 0;

            while ((entry = zis.getNextEntry()) != null && fileCount < MAX_FILES) {
                String name = entry.getName();

                // Skip directories
                if (entry.isDirectory()) {
                    zis.closeEntry();
                    continue;
                }

                // Skip secrets content (keep metadata but not values)
                if (name.contains("/secrets/") && !name.endsWith(".yaml")) {
                    zis.closeEntry();
                    continue;
                }

                // Only extract text files
                if (!isTextFile(name)) {
                    zis.closeEntry();
                    continue;
                }

                // Read content with size limit
                byte[] buffer = new byte[8192];
                StringBuilder sb = new StringBuilder();
                int read;
                int totalRead = 0;

                while ((read = zis.read(buffer)) != -1) {
                    totalRead += read;
                    if (totalRead <= MAX_FILE_CHARS) {
                        sb.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
                    }
                }

                if (totalRead > MAX_FILE_CHARS) {
                    sb.append("\n[File truncated at ")
                      .append(MAX_FILE_CHARS)
                      .append(" characters — original size: ")
                      .append(totalRead)
                      .append(" bytes]");
                }

                // Normalize path separators and remove top-level date folder
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

    /**
     * Normalize the ZIP entry path:
     * - Replace backslashes with forward slashes
     * - Remove the top-level date folder (report-DD-MM-YYYY_HH-MM-SS/)
     * - Remove the "reports/" prefix
     * Result: "kafkas/my-cluster.yaml", "pods/broker-0.log", etc.
     */
    private String normalizePath(String path) {
        path = path.replace('\\', '/');
        // Remove top-level date folder: report-08-01-2026_18-47-12/reports/
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