package com.redhat.kafka.diag.tools;

import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

/**
 * Report Upload Tool — Phase 4.
 *
 * Parses and analyzes a Strimzi report.sh output uploaded by the user via
 * the Web UI. Allows diagnosing Kafka clusters without direct cluster access —
 * useful when the cluster is in a customer environment or a different namespace.
 *
 * The uploaded report content is stored per-thread so concurrent requests
 * are isolated from each other. DiagnosticResource clears the ThreadLocal
 * in a finally block after each request to prevent memory leaks.
 */
@ApplicationScoped
public class ReportUploadTool {

    private static final Logger LOG = Logger.getLogger(ReportUploadTool.class);

    // Maximum characters to return per section to stay within LLM context limits
    private static final int MAX_SECTION_CHARS = 2000;

    // Thread-local storage — one report per active request thread
    private static final ThreadLocal<String> uploadedReport = new ThreadLocal<>();

    @Tool("Analyze a previously uploaded Strimzi report.sh output. " +
          "Use this when the user has uploaded a report file instead of connecting directly to the cluster. " +
          "Call this tool first when a report has been uploaded, before trying any live cluster tools. " +
          "Pass an aspect like: pods, topics, events, logs, config, connect, mirror — or 'summary' for an overview.")
    public String analyzeUploadedReport(String aspect) {
        String report = uploadedReport.get();
        if (report == null || report.isBlank()) {
            return "[No report has been uploaded. " +
                   "Please upload a Strimzi report.sh output file via the web interface, " +
                   "or ask a question about a live cluster instead.]";
        }

        LOG.infof("Analyzing uploaded report — aspect: %s, size: %d chars",
                aspect, report.length());

        String aspectLower = aspect == null ? "summary" : aspect.toLowerCase();

        if (aspectLower.contains("pod") || aspectLower.contains("status")) {
            return extractSection(report, "pods", "==== Pods", "====");
        }
        if (aspectLower.contains("topic")) {
            return extractSection(report, "topics", "==== KafkaTopic", "====");
        }
        if (aspectLower.contains("event")) {
            return extractSection(report, "events", "==== Events", "====");
        }
        if (aspectLower.contains("log")) {
            return extractSection(report, "logs", "==== Logs", "====");
        }
        if (aspectLower.contains("config")) {
            return extractSection(report, "configuration", "==== Kafka", "====");
        }
        if (aspectLower.contains("connect") || aspectLower.contains("connector")) {
            return extractSection(report, "connect", "==== KafkaConnect", "====");
        }
        if (aspectLower.contains("mirror")) {
            return extractSection(report, "mirrormaker", "==== KafkaMirrorMaker", "====");
        }

        // Default — general summary
        return buildSummary(report);
    }

    // ----------------------------------------------------------------
    // Static lifecycle methods — called by DiagnosticResource
    // ----------------------------------------------------------------

    /** Store the uploaded report for the current request thread. */
    public static void setUploadedReport(String content) {
        uploadedReport.set(content);
    }

    /** Clear the report after the request completes. Call in a finally block. */
    public static void clearUploadedReport() {
        uploadedReport.remove();
    }

    /** Returns true if a report is currently loaded for this thread. */
    public static boolean hasUploadedReport() {
        String r = uploadedReport.get();
        return r != null && !r.isBlank();
    }

    // ----------------------------------------------------------------
    // Internal parsing helpers
    // ----------------------------------------------------------------

    private String extractSection(String report, String sectionName,
                                  String startMarker, String endMarker) {
        int start = report.indexOf(startMarker);
        if (start == -1) {
            return "No " + sectionName + " section found in the uploaded report.\n\n" +
                   buildSummary(report);
        }

        int nextSection = report.indexOf(endMarker, start + startMarker.length());
        String section = nextSection == -1
                ? report.substring(start)
                : report.substring(start, nextSection);

        if (section.length() > MAX_SECTION_CHARS) {
            section = section.substring(0, MAX_SECTION_CHARS) +
                      "\n\n[Section truncated — showing first " + MAX_SECTION_CHARS + " chars]";
        }

        return "=== " + sectionName.toUpperCase() + " from uploaded report ===\n\n" + section;
    }

    private String buildSummary(String report) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== UPLOADED REPORT SUMMARY ===\n\n");
        sb.append("Report size: ").append(report.length()).append(" characters\n\n");

        String[] sections = {
            "Kafka", "KafkaTopic", "KafkaUser", "KafkaConnect",
            "KafkaConnector", "KafkaMirrorMaker", "Pods", "Events", "Logs"
        };

        sb.append("Sections detected:\n");
        for (String section : sections) {
            if (report.contains("==== " + section) || report.contains("=== " + section)) {
                sb.append("  - ").append(section).append("\n");
            }
        }

        sb.append("\nReport excerpt (first 1500 characters):\n\n");
        sb.append(report, 0, Math.min(1500, report.length()));
        if (report.length() > 1500) {
            sb.append("\n\n[... ask about specific sections: pods, topics, events, logs, config, connect, mirror]");
        }

        return sb.toString();
    }
}