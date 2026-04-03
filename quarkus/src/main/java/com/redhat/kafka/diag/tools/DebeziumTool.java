package com.redhat.kafka.diag.tools;

import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Debezium Diagnostic Tool — PLACEHOLDER.
 *
 * Full implementation is planned for Phase 6.
 *
 * When implemented, this tool will:
 * - Inspect Debezium connectors running on Kafka Connect
 * - Check connector status, lag, and offset topics
 * - Identify common Debezium misconfiguration issues
 * - Analyze snapshot and streaming mode transitions
 */
@ApplicationScoped
public class DebeziumTool {

    @Tool("Diagnose Debezium CDC connectors running on Kafka Connect. " +
          "Checks connector status, lag, and common configuration issues.")
    public String diagnoseDebezium(String namespace) {
        return String.format("""
                [DEBEZIUM DIAGNOSTICS — Not yet implemented]

                To inspect Debezium manually in namespace %s:

                1. List Kafka Connect pods:
                   oc get pods -n %s | grep connect

                2. List all connectors via Kafka Connect REST API:
                   oc exec -n %s <connect-pod> -- curl -s http://localhost:8083/connectors

                3. Check a specific connector status:
                   oc exec -n %s <connect-pod> -- curl -s http://localhost:8083/connectors/<name>/status

                4. Check connector logs:
                   oc logs -n %s <connect-pod> | grep -i "debezium\\|error\\|warn"

                Full Debezium diagnostics will be available in Phase 6.
                """, namespace, namespace, namespace, namespace, namespace);
    }
}
