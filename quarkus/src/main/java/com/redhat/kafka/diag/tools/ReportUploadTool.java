package com.redhat.kafka.diag.tools;

import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.Map;
import java.util.TreeMap;

/**
 * Report Upload Tool — Phase 4.
 *
 * Analyzes a Strimzi report.sh ZIP output that has been uploaded via the Web UI
 * and pre-processed by DiagnosticResource into a structured map of files.
 *
 * The ZIP structure is:
 *   report-<date>/reports/
 *     kafkas/<cluster>.yaml
 *     kafkatopics/<topic>.yaml
 *     pods/<pod>.yaml or pods/<pod>.log
 *     events/events.txt
 *     ...
 *
 * DiagnosticResource extracts all text files from the ZIP and stores them
 * as a Map<relativePath, content> in the ThreadLocal. This tool then queries
 * that map based on what the agent needs to know.
 */
@ApplicationScoped
public class ReportUploadTool {

    private static final Logger LOG = Logger.getLogger(ReportUploadTool.class);

    private static final int MAX_CONTENT_CHARS = 3000;

    // Thread-local: map of relative path → file content extracted from the ZIP
    private static final ThreadLocal<Map<String, String>> reportFiles = new ThreadLocal<>();

    @Tool("Analyze a previously uploaded Strimzi report.sh ZIP output. " +
          "Use this when the user has uploaded a report ZIP file instead of connecting directly to the cluster. " +
          "Call this tool first when a report has been uploaded. " +
          "Pass an aspect to focus on: 'summary', 'kafka', 'topics', 'pods', 'events', 'connect', 'mirror', 'secrets', or a specific filename.")
    public String analyzeUploadedReport(String aspect) {
        Map<String, String> files = reportFiles.get();
        if (files == null || files.isEmpty()) {
            return "[No report has been uploaded. " +
                   "Please upload a Strimzi report.sh ZIP file via the web interface.]";
        }

        LOG.infof("Analyzing uploaded report — aspect: %s, files: %d", aspect, files.size());

        String aspectLower = aspect == null ? "summary" : aspect.toLowerCase();

        // Summary — list all files found
        if (aspectLower.contains("summary") || aspectLower.contains("overview")) {
            return buildSummary(files);
        }

        // Search for files matching the requested aspect
        String prefix = mapAspectToPrefix(aspectLower);
        if (prefix != null) {
            return extractByPrefix(files, prefix);
        }

        // Try to find a specific file by name
        for (Map.Entry<String, String> entry : files.entrySet()) {
            if (entry.getKey().toLowerCase().contains(aspectLower)) {
                return formatFile(entry.getKey(), entry.getValue());
            }
        }

        // Default — return summary
        return buildSummary(files);
    }

    // ----------------------------------------------------------------
    // Static lifecycle methods — called by DiagnosticResource
    // ----------------------------------------------------------------

    /** Store the extracted report files for the current request thread. */
    public static void setReportFiles(Map<String, String> files) {
        reportFiles.set(files);
    }

    /** Clear report files after the request completes. Call in a finally block. */
    public static void clearReportFiles() {
        reportFiles.remove();
    }

    /** Returns true if report files are loaded for this thread. */
    public static boolean hasReportFiles() {
        Map<String, String> files = reportFiles.get();
        return files != null && !files.isEmpty();
    }

    // ----------------------------------------------------------------
    // Internal helpers
    // ----------------------------------------------------------------

    private String mapAspectToPrefix(String aspect) {
        if (aspect.contains("kafka") && !aspect.contains("topic") &&
            !aspect.contains("connect") && !aspect.contains("mirror")) return "kafkas/";
        if (aspect.contains("topic"))   return "kafkatopics/";
        if (aspect.contains("pod") || aspect.contains("log")) return "pods/";
        if (aspect.contains("event"))   return "events/";
        if (aspect.contains("connect") && !aspect.contains("mirror")) return "kafkaconnects/";
        if (aspect.contains("mirror"))  return "kafkamirrormaker";
        if (aspect.contains("secret"))  return "secrets/";
        if (aspect.contains("service")) return "services/";
        if (aspect.contains("config"))  return "configmaps/";
        if (aspect.contains("role"))    return "clusterroles/";
        if (aspect.contains("node"))    return "kafkanodepools/";
        return null;
    }

    private String extractByPrefix(Map<String, String> files, String prefix) {
        StringBuilder sb = new StringBuilder();
        int count = 0;
        int totalChars = 0;

        for (Map.Entry<String, String> entry : files.entrySet()) {
            // Match by directory prefix anywhere in the path
            String key = entry.getKey();
            boolean matches = key.contains("/" + prefix) || key.contains("\\" + prefix);
            if (!matches) continue;

            count++;
            String content = entry.getValue();
            if (totalChars + content.length() > MAX_CONTENT_CHARS) {
                content = content.substring(0, Math.max(0, MAX_CONTENT_CHARS - totalChars)) +
                          "\n[truncated]";
            }

            sb.append("--- ").append(key).append(" ---\n");
            sb.append(content).append("\n\n");
            totalChars += content.length();

            if (totalChars >= MAX_CONTENT_CHARS) {
                sb.append("[Additional files omitted — ask for a specific file by name]\n");
                break;
            }
        }

        if (count == 0) {
            return "No files found for aspect '" + prefix + "' in the uploaded report.\n\n" +
                   buildSummary(files);
        }

        return "=== " + prefix.toUpperCase().replace("/", "") +
               " from uploaded report (" + count + " files) ===\n\n" + sb;
    }

    private String buildSummary(Map<String, String> files) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== UPLOADED REPORT SUMMARY ===\n\n");
        sb.append("Total files: ").append(files.size()).append("\n\n");

        // Group by directory
        Map<String, Integer> dirs = new TreeMap<>();
        for (String path : files.keySet()) {
            int slash = path.lastIndexOf('/');
            String dir = slash >= 0 ? path.substring(0, slash) : "(root)";
            // Get the last meaningful directory segment
            int prevSlash = dir.lastIndexOf('/');
            String shortDir = prevSlash >= 0 ? dir.substring(prevSlash + 1) : dir;
            dirs.merge(shortDir, 1, Integer::sum);
        }

        sb.append("Contents:\n");
        for (Map.Entry<String, Integer> e : dirs.entrySet()) {
            sb.append("  ").append(e.getKey())
              .append(": ").append(e.getValue()).append(" file(s)\n");
        }

        sb.append("\nAsk about: kafka, topics, pods, events, connect, mirror, secrets, configmaps, roles, nodes");
        return sb.toString();
    }

    private String formatFile(String path, String content) {
        if (content.length() > MAX_CONTENT_CHARS) {
            content = content.substring(0, MAX_CONTENT_CHARS) + "\n[truncated]";
        }
        return "--- " + path + " ---\n\n" + content;
    }
}