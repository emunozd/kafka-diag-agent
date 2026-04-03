package com.redhat.kafka.diag.tools;

import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Strimzi diagnostic report tool.
 *
 * Downloads and executes the official Strimzi report.sh script against a
 * given namespace. The script collects comprehensive diagnostics including
 * all Kafka resources, operator logs, pod states, and events.
 *
 * The script is downloaded once and cached in /tmp for 1 hour to avoid
 * redundant network calls during the same session.
 *
 * Output is truncated to 8000 characters to stay within the LLM context window.
 */
@ApplicationScoped
public class StrimziReportTool {

    private static final Logger LOG = Logger.getLogger(StrimziReportTool.class);
    private static final String REPORT_URL =
            "https://raw.githubusercontent.com/strimzi/strimzi-kafka-operator/refs/heads/main/tools/report.sh";
    private static final int TIMEOUT_SECONDS = 120;
    private static final int MAX_OUTPUT_CHARS = 8000;

    @Tool("Run the official Strimzi diagnostic report script against a namespace. " +
          "Returns a comprehensive report of all Kafka-related resources, logs and status. " +
          "Use this for deep diagnostics when basic oc commands are not enough.")
    public String runStrimziReport(String namespace) {
        LOG.infof("Running Strimzi report for namespace: %s", namespace);

        try {
            Path scriptPath = downloadReportScript();
            return executeReport(scriptPath, namespace);
        } catch (Exception e) {
            LOG.errorf(e, "Failed to run Strimzi report");
            return "ERROR running Strimzi report: " + e.getMessage() +
                   "\nRun manually: curl -s " + REPORT_URL + " | bash -s -- -n " + namespace;
        }
    }

    private Path downloadReportScript() throws Exception {
        Path scriptPath = Path.of("/tmp/strimzi-report.sh");

        // Reuse cached script if it is less than 1 hour old
        if (Files.exists(scriptPath)) {
            long ageMillis = System.currentTimeMillis() - Files.getLastModifiedTime(scriptPath).toMillis();
            if (ageMillis < 3_600_000L) {
                return scriptPath;
            }
        }

        LOG.info("Downloading Strimzi report.sh...");
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(REPORT_URL))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("Failed to download report.sh: HTTP " + response.statusCode());
        }

        Files.writeString(scriptPath, response.body(),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        Files.setPosixFilePermissions(scriptPath, Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE
        ));

        return scriptPath;
    }

    private String executeReport(Path scriptPath, String namespace) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("bash", scriptPath.toString(), "-n", namespace);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            return "ERROR: Strimzi report timed out after " + TIMEOUT_SECONDS + "s";
        }

        String output = new BufferedReader(new InputStreamReader(process.getInputStream()))
                .lines()
                .collect(Collectors.joining("\n"));

        // Truncate to avoid consuming the entire LLM context window
        if (output.length() > MAX_OUTPUT_CHARS) {
            output = output.substring(0, MAX_OUTPUT_CHARS) +
                     "\n\n[REPORT TRUNCATED — showing first " + MAX_OUTPUT_CHARS + " characters]";
        }

        return output.isBlank()
                ? "Report produced no output for namespace: " + namespace
                : output;
    }
}
