package com.redhat.kafka.diag.tools;

import dev.langchain4j.agent.tool.Tool;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.GenericKubernetesResourceList;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;
import io.fabric8.openshift.client.OpenShiftClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Debezium Diagnostic Tool — Phase 6.
 *
 * Detects and diagnoses Debezium CDC connectors running on KafkaConnect
 * in a given namespace. Debezium connectors are KafkaConnector resources
 * whose class starts with "io.debezium".
 *
 * Inspects:
 * - KafkaConnect instances in the namespace
 * - KafkaConnector resources with Debezium connector classes
 * - Connector status (RUNNING, FAILED, PAUSED, UNASSIGNED)
 * - Common configuration issues (missing database credentials, offset topics, etc.)
 * - KafkaConnector conditions from the Strimzi status block
 */
@ApplicationScoped
public class DebeziumTool {

    private static final Logger LOG = Logger.getLogger(DebeziumTool.class);

    private static final String STRIMZI_GROUP   = "kafka.strimzi.io";
    private static final String STRIMZI_VERSION = "v1beta2";

    // Known Debezium connector class prefixes
    private static final List<String> DEBEZIUM_CLASSES = List.of(
            "io.debezium.connector.mysql",
            "io.debezium.connector.postgresql",
            "io.debezium.connector.sqlserver",
            "io.debezium.connector.mongodb",
            "io.debezium.connector.oracle",
            "io.debezium.connector.db2",
            "io.debezium.connector.mariadb",
            "io.debezium"
    );

    @Inject
    OpenShiftClient client;

    @Tool("Diagnose Debezium CDC connectors running on Kafka Connect in a namespace. " +
          "Detects all KafkaConnector resources using Debezium connector classes, " +
          "checks their status, conditions, and common configuration issues. " +
          "Use this when the user asks about CDC, Debezium, change data capture, " +
          "or connector issues related to database replication.")
    public String diagnoseDebezium(String namespace) {
        LOG.infof("Diagnosing Debezium connectors in namespace: %s", namespace);
        StringBuilder sb = new StringBuilder();
        sb.append("=== Debezium CDC Diagnostic — namespace: ").append(namespace).append(" ===\n\n");

        // Step 1: find KafkaConnect instances
        List<GenericKubernetesResource> connects = listStrimzi(namespace, "kafkaconnects");
        if (connects.isEmpty()) {
            sb.append("No KafkaConnect instances found in namespace ").append(namespace).append(".\n");
            sb.append("Debezium connectors require a running KafkaConnect cluster.\n");
            return sb.toString();
        }

        sb.append("KafkaConnect instances:\n");
        for (GenericKubernetesResource connect : connects) {
            String name = connect.getMetadata().getName();
            String status = extractConditionStatus(connect);
            sb.append("  - ").append(name).append(" [").append(status).append("]\n");
        }
        sb.append("\n");

        // Step 2: find all KafkaConnectors and filter for Debezium
        List<GenericKubernetesResource> allConnectors = listStrimzi(namespace, "kafkaconnectors");
        List<GenericKubernetesResource> debeziumConnectors = new ArrayList<>();

        for (GenericKubernetesResource connector : allConnectors) {
            String connClass = extractConnectorClass(connector);
            if (isDebeziumConnector(connClass)) {
                debeziumConnectors.add(connector);
            }
        }

        if (debeziumConnectors.isEmpty()) {
            sb.append("No Debezium connectors found in namespace ").append(namespace).append(".\n\n");
            sb.append("Total KafkaConnectors found: ").append(allConnectors.size()).append("\n");
            if (!allConnectors.isEmpty()) {
                sb.append("Non-Debezium connectors:\n");
                for (GenericKubernetesResource c : allConnectors) {
                    sb.append("  - ").append(c.getMetadata().getName())
                      .append(" (class: ").append(extractConnectorClass(c)).append(")\n");
                }
            }
            return sb.toString();
        }

        sb.append("Debezium connectors found: ").append(debeziumConnectors.size()).append("\n\n");

        // Step 3: analyze each Debezium connector
        for (GenericKubernetesResource connector : debeziumConnectors) {
            String name = connector.getMetadata().getName();
            sb.append("--- Connector: ").append(name).append(" ---\n");

            // Connector class
            String connClass = extractConnectorClass(connector);
            sb.append("Class:  ").append(connClass).append("\n");

            // Status conditions
            String conditionStatus = extractConditionStatus(connector);
            String conditionReason = extractConditionReason(connector);
            String conditionMessage = extractConditionMessage(connector);
            sb.append("Status: ").append(conditionStatus).append("\n");

            if (!conditionReason.isBlank()) {
                sb.append("Reason: ").append(conditionReason).append("\n");
            }
            if (!conditionMessage.isBlank()) {
                sb.append("Message: ").append(conditionMessage).append("\n");
            }

            // Extract key config properties from spec
            Map<?, ?> spec = getMap(connector.getAdditionalProperties(), "spec");
            if (spec != null) {
                Map<?, ?> configMap = getMap(spec, "config");
                if (configMap != null) {
                    sb.append("Configuration:\n");
                    String[] keyProps = {
                        "connector.class",
                        "database.hostname", "database.port", "database.dbname",
                        "database.server.name", "topic.prefix",
                        "database.history.kafka.topic",
                        "schema.history.internal.kafka.topic",
                        "offset.storage.topic",
                        "snapshot.mode",
                        "tasks.max"
                    };
                    for (String key : keyProps) {
                        Object val = configMap.get(key);
                        if (val != null) {
                            sb.append("  ").append(key).append(": ").append(val).append("\n");
                        }
                    }

                    // Check for common misconfigurations
                    sb.append("\nConfiguration checks:\n");
                    checkDebeziumConfig(sb, configMap, connClass);
                }
            }

            sb.append("\n");
        }

        return sb.toString();
    }

    // ----------------------------------------------------------------
    // Configuration checks
    // ----------------------------------------------------------------

    private void checkDebeziumConfig(StringBuilder sb, Map<?, ?> config, String connClass) {
        boolean hasIssues = false;

        // Check database hostname
        if (!config.containsKey("database.hostname") && !config.containsKey("mongodb.hosts")) {
            sb.append("  ⚠ WARNING: database.hostname not configured\n");
            hasIssues = true;
        }

        // Check topic prefix (replaces database.server.name in newer versions)
        boolean hasPrefix = config.containsKey("topic.prefix");
        boolean hasServerName = config.containsKey("database.server.name");
        if (!hasPrefix && !hasServerName) {
            sb.append("  ⚠ WARNING: topic.prefix not configured — required for event routing\n");
            hasIssues = true;
        }

        // Check schema history topic (MySQL, SQL Server, Oracle)
        if (connClass.contains("mysql") || connClass.contains("sqlserver") || connClass.contains("oracle")) {
            boolean hasHistory = config.containsKey("schema.history.internal.kafka.topic")
                    || config.containsKey("database.history.kafka.topic");
            if (!hasHistory) {
                sb.append("  ⚠ WARNING: schema history topic not configured\n");
                hasIssues = true;
            }
        }

        // Check snapshot mode
        Object snapshotMode = config.get("snapshot.mode");
        if (snapshotMode != null) {
            sb.append("  ℹ Snapshot mode: ").append(snapshotMode).append("\n");
        }

        // Check tasks.max
        Object tasksMax = config.get("tasks.max");
        if (tasksMax != null && !"1".equals(tasksMax.toString())) {
            sb.append("  ⚠ WARNING: tasks.max=").append(tasksMax)
              .append(" — Debezium connectors only support tasks.max=1\n");
            hasIssues = true;
        }

        if (!hasIssues) {
            sb.append("  ✓ No obvious configuration issues detected\n");
        }
    }

    // ----------------------------------------------------------------
    // Kubernetes helpers
    // ----------------------------------------------------------------

    private List<GenericKubernetesResource> listStrimzi(String namespace, String plural) {
        try {
            ResourceDefinitionContext ctx = new ResourceDefinitionContext.Builder()
                    .withGroup(STRIMZI_GROUP)
                    .withVersion(STRIMZI_VERSION)
                    .withPlural(plural)
                    .withNamespaced(true)
                    .build();
            GenericKubernetesResourceList list = client
                    .genericKubernetesResources(ctx)
                    .inNamespace(namespace)
                    .list();
            return list.getItems();
        } catch (Exception e) {
            LOG.warnf("Failed to list %s in namespace %s: %s", plural, namespace, e.getMessage());
            return List.of();
        }
    }

    private boolean isDebeziumConnector(String connClass) {
        if (connClass == null || connClass.isBlank()) return false;
        return DEBEZIUM_CLASSES.stream().anyMatch(connClass::startsWith);
    }

    @SuppressWarnings("unchecked")
    private String extractConnectorClass(GenericKubernetesResource resource) {
        try {
            Map<?, ?> spec = getMap(resource.getAdditionalProperties(), "spec");
            if (spec == null) return "unknown";
            Map<?, ?> config = getMap(spec, "config");
            if (config == null) {
                // class may be directly in spec
                Object cls = spec.get("class");
                return cls != null ? cls.toString() : "unknown";
            }
            Object cls = config.get("connector.class");
            return cls != null ? cls.toString() : "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }

    private String extractConditionStatus(GenericKubernetesResource resource) {
        return extractFromConditions(resource, "type", "Ready", "status", "Unknown");
    }

    private String extractConditionReason(GenericKubernetesResource resource) {
        return extractFromConditions(resource, "type", "Ready", "reason", "");
    }

    private String extractConditionMessage(GenericKubernetesResource resource) {
        return extractFromConditions(resource, "type", "Ready", "message", "");
    }

    @SuppressWarnings("unchecked")
    private String extractFromConditions(GenericKubernetesResource resource,
                                          String matchKey, String matchValue,
                                          String returnKey, String defaultValue) {
        try {
            Map<?, ?> status = getMap(resource.getAdditionalProperties(), "status");
            if (status == null) return defaultValue;
            Object conditionsObj = status.get("conditions");
            if (!(conditionsObj instanceof List)) return defaultValue;
            List<?> conditions = (List<?>) conditionsObj;
            for (Object c : conditions) {
                if (!(c instanceof Map)) continue;
                Map<?, ?> cond = (Map<?, ?>) c;
                Object val = cond.get(matchKey);
                if (matchValue.equals(val != null ? val.toString() : "")) {
                    Object ret = cond.get(returnKey);
                    return ret != null ? ret.toString() : defaultValue;
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return defaultValue;
    }

    @SuppressWarnings("unchecked")
    private Map<?, ?> getMap(Map<?, ?> parent, String key) {
        if (parent == null) return null;
        Object val = parent.get(key);
        return val instanceof Map ? (Map<?, ?>) val : null;
    }
}