package com.redhat.kafka.diag.tools;

import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Debezium Diagnostic Tool — PLACEHOLDER.
 * Será implementado en Fase 6.
 */
@ApplicationScoped
public class DebeziumTool {

    @Tool("Diagnose Debezium CDC connectors running on Kafka Connect. " +
          "Checks connector status, lag, and common configuration issues.")
    public String diagnoseDebezium(String namespace) {
        return """
                [DEBEZIUM DIAGNOSTICS — Not yet implemented]

                For now, check Debezium manually:

                1. List Kafka Connect pods:
                   oc get pods -n %s | grep connect

                2. Check connector status via Kafka Connect REST API:
                   oc exec -n %s <connect-pod> -- curl -s http://localhost:8083/connectors

                3. Check specific connector status:
                   oc exec -n %s <connect-pod> -- curl -s http://localhost:8083/connectors/<name>/status

                4. Check connector logs:
                   oc logs -n %s <connect-pod> | grep -i "debezium\\|error\\|warn"

                This tool will be fully implemented in a future release.
                """.formatted(namespace, namespace, namespace, namespace);
    }
}
