package com.redhat.kafka.diag.resource;

import com.redhat.kafka.diag.agent.KafkaDiagnosticAgent;
import com.redhat.kafka.diag.config.AgentConfig;
import com.redhat.kafka.diag.tools.ReportUploadTool;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

/**
 * REST endpoint for the Kafka Diagnostic Agent.
 *
 * POST /api/diagnose       — diagnose using live cluster access
 * POST /api/upload-report  — store an uploaded report.sh output
 * POST /api/diagnose-report — diagnose using the uploaded report (no cluster access needed)
 * GET  /api/health         — health check
 */
@Path("/api")
public class DiagnosticResource {

    private static final Logger LOG = Logger.getLogger(DiagnosticResource.class);

    // Maximum report size accepted — 2MB
    private static final int MAX_REPORT_SIZE = 2 * 1024 * 1024;

    @Inject
    KafkaDiagnosticAgent agent;

    @Inject
    AgentConfig config;

    // ----------------------------------------------------------------
    // POST /api/diagnose — live cluster mode
    // ----------------------------------------------------------------

    /**
     * Diagnose using live cluster access via the Kubernetes API.
     * The namespace is prepended to the question so all tool calls target it:
     *   "[namespace: my-ns] why is mirrormaker not working"
     */
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
        String question  = String.format("[namespace: %s] %s", namespace, request.question());

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
    // POST /api/upload-report — store uploaded report
    // ----------------------------------------------------------------

    /**
     * Accept a plain-text Strimzi report.sh output and store it in the
     * ThreadLocal for the subsequent diagnose-report call.
     *
     * The client must call /api/diagnose-report on the same session after
     * uploading. The report is cleared after each diagnosis.
     */
    @POST
    @Path("/upload-report")
    @Consumes(MediaType.TEXT_PLAIN)
    @Produces(MediaType.APPLICATION_JSON)
    public Response uploadReport(String reportContent) {
        if (reportContent == null || reportContent.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ErrorResponse("Report content is empty"))
                    .build();
        }

        if (reportContent.length() > MAX_REPORT_SIZE) {
            return Response.status(Response.Status.REQUEST_ENTITY_TOO_LARGE)
                    .entity(new ErrorResponse("Report exceeds maximum size of 2MB"))
                    .build();
        }

        ReportUploadTool.setUploadedReport(reportContent);

        LOG.infof("Report uploaded — size: %d chars", reportContent.length());

        return Response.ok(new UploadResponse(
                "Report uploaded successfully",
                reportContent.length()
        )).build();
    }

    // ----------------------------------------------------------------
    // POST /api/diagnose-report — report-based diagnosis mode
    // ----------------------------------------------------------------

    /**
     * Diagnose using a previously uploaded report instead of live cluster access.
     * The agent will call ReportUploadTool instead of KubernetesTool.
     * The report is cleared after the response is sent.
     */
    @POST
    @Path("/diagnose-report")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response diagnoseReport(DiagnoseRequest request) {
        if (request == null || request.question() == null || request.question().isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ErrorResponse("question is required"))
                    .build();
        }

        if (!ReportUploadTool.hasUploadedReport()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ErrorResponse(
                            "No report uploaded. Please upload a report first via POST /api/upload-report"))
                    .build();
        }

        // Tell the agent to use the uploaded report — no namespace needed
        String question = "[report-mode: true] " + request.question() +
                          " — use the analyzeUploadedReport tool to read the cluster data.";

        LOG.infof("Diagnosing (report): question=%s", request.question());

        try {
            String answer = stripThinkBlocks(agent.diagnose(question));
            return Response.ok(new DiagnoseResponse(answer, "from-report", true)).build();
        } catch (Exception e) {
            LOG.errorf(e, "Error during report diagnosis");
            return Response.serverError()
                    .entity(new ErrorResponse("Diagnosis failed: " + e.getMessage()))
                    .build();
        } finally {
            // Always clear the report after use to prevent ThreadLocal leaks
            ReportUploadTool.clearUploadedReport();
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

    public record UploadResponse(String message, int size) {}

    public record ErrorResponse(String error) {}
}