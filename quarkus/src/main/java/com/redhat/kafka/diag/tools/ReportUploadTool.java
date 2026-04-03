package com.redhat.kafka.diag.tools;

import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

/**
 * Report Upload Tool — Phase 4 placeholder.
 *
 * When fully implemented in Phase 4, this tool will allow the agent
 * to analyze a Strimzi report.sh output uploaded by the user via the
 * Web UI, without needing direct cluster access.
 *
 * This is useful when:
 * - The Kafka cluster is in a different namespace or cluster
 * - The user has exported a report from a customer environment
 * - The cluster is not reachable from the agent pod
 *
 * The uploaded report content is stored per-thread so it is isolated
 * between concurrent requests.
 */
@ApplicationScoped
public class ReportUploadTool {

    private static final Logger LOG = Logger.getLogger(ReportUploadTool.class);

    // Thread-local storage for the uploaded report — one per request
    private static final ThreadLocal<String> uploadedReport = new ThreadLocal<>();

    @Tool("Analyze a previously uploaded Strimzi report.sh output. " +
          "Use this when the user has uploaded a report file instead of connecting directly to the cluster.")
    public String analyzeUploadedReport(String aspect) {
        // TODO Phase 4: implement real analysis of the uploaded report
        String report = uploadedReport.get();
        if (report == null || report.isBlank()) {
            return "[No report has been uploaded yet. " +
                   "Please upload a Strimzi report.sh output file via the web interface.]";
        }
        return "[Report upload analysis not yet implemented — available in Phase 4]";
    }

    /**
     * Called by DiagnosticResource when the user uploads a report file.
     * Must be called before invoking the agent on the same thread.
     */
    public static void setUploadedReport(String content) {
        uploadedReport.set(content);
    }

    /**
     * Clears the uploaded report after the request completes.
     * Should be called in a finally block to prevent memory leaks.
     */
    public static void clearUploadedReport() {
        uploadedReport.remove();
    }
}
