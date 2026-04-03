package com.redhat.kafka.diag.tools;

import dev.langchain4j.agent.tool.Tool;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.GenericKubernetesResourceList;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;
import io.fabric8.openshift.client.OpenShiftClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Kubernetes / OpenShift tool for live cluster inspection.
 *
 * Uses the Fabric8 OpenShiftClient injected by Quarkus CDI.
 * The ServiceAccount kafka-diag-sa must have ClusterRole kafka-diag-reader
 * with read access to all Strimzi CRDs and core Kubernetes resources.
 *
 * Strimzi resources (Kafka, KafkaTopic, etc.) are accessed via
 * genericKubernetesResources() with the kafka.strimzi.io/v1beta2 API group
 * to avoid model class version conflicts.
 */
@ApplicationScoped
public class KubernetesTool {

    private static final Logger LOG = Logger.getLogger(KubernetesTool.class);

    private static final String STRIMZI_GROUP   = "kafka.strimzi.io";
    private static final String STRIMZI_VERSION = "v1beta2";

    @Inject
    OpenShiftClient client;

    // ----------------------------------------------------------------
    // Kafka cluster resources
    // ----------------------------------------------------------------

    @Tool("Get all Kafka clusters in a namespace. Returns a summary of cluster name, replicas, and ready status.")
    public String getKafkaClusters(String namespace) {
        return listStrimziResources(namespace, "kafkas", "Kafka");
    }

    @Tool("Get all KafkaTopics in a namespace with their partition count, replication factor, and status.")
    public String getKafkaTopics(String namespace) {
        return listStrimziResources(namespace, "kafkatopics", "KafkaTopic");
    }

    @Tool("Get all KafkaUsers in a namespace.")
    public String getKafkaUsers(String namespace) {
        return listStrimziResources(namespace, "kafkausers", "KafkaUser");
    }

    @Tool("Get KafkaConnect and KafkaConnector resources in a namespace with their status and conditions.")
    public String getKafkaConnect(String namespace) {
        String connects    = listStrimziResources(namespace, "kafkaconnects",    "KafkaConnect");
        String connectors  = listStrimziResources(namespace, "kafkaconnectors",  "KafkaConnector");
        return "=== KafkaConnect ===\n" + connects +
               "\n=== KafkaConnectors ===\n" + connectors;
    }

    @Tool("Get MirrorMaker2 resources in a namespace with their status and connector conditions.")
    public String getMirrorMaker2(String namespace) {
        return listStrimziResources(namespace, "kafkamirrormaker2s", "KafkaMirrorMaker2");
    }

    @Tool("Get KafkaBridge resources in a namespace.")
    public String getKafkaBridge(String namespace) {
        return listStrimziResources(namespace, "kafkabridges", "KafkaBridge");
    }

    // ----------------------------------------------------------------
    // Core Kubernetes resources
    // ----------------------------------------------------------------

    @Tool("Get all pods in a namespace with their status. Useful to check if Kafka broker or ZooKeeper pods are running.")
    public String getPods(String namespace) {
        try {
            List<Pod> pods = client.pods().inNamespace(namespace).list().getItems();
            if (pods.isEmpty()) {
                return "No pods found in namespace: " + namespace;
            }
            return pods.stream()
                    .map(p -> String.format("%-50s %-12s %d/%d restarts=%d",
                            p.getMetadata().getName(),
                            p.getStatus().getPhase(),
                            p.getStatus().getContainerStatuses() == null ? 0 :
                                p.getStatus().getContainerStatuses().stream()
                                    .filter(c -> Boolean.TRUE.equals(c.getReady())).count(),
                            p.getStatus().getContainerStatuses() == null ? 0 :
                                p.getStatus().getContainerStatuses().size(),
                            p.getStatus().getContainerStatuses() == null ? 0 :
                                p.getStatus().getContainerStatuses().stream()
                                    .mapToInt(c -> c.getRestartCount() != null ? c.getRestartCount() : 0)
                                    .sum()
                    ))
                    .collect(Collectors.joining("\n"));
        } catch (Exception e) {
            LOG.errorf(e, "Failed to list pods in namespace %s", namespace);
            return "ERROR listing pods: " + e.getMessage();
        }
    }

    @Tool("Get the last 100 log lines of a specific pod. Use to inspect broker errors, connector failures, etc.")
    public String getPodLogs(String namespace, String podName) {
        try {
            String logs = client.pods()
                    .inNamespace(namespace)
                    .withName(podName)
                    .tailingLines(100)
                    .getLog();
            return logs == null || logs.isBlank()
                    ? "No logs available for pod: " + podName
                    : logs;
        } catch (Exception e) {
            LOG.errorf(e, "Failed to get logs for pod %s in namespace %s", podName, namespace);
            return "ERROR getting pod logs: " + e.getMessage();
        }
    }

    @Tool("Get Kafka-related events in a namespace sorted by time. Useful to spot recent errors and warnings.")
    public String getKafkaEvents(String namespace) {
        try {
            List<Event> events = client.v1().events()
                    .inNamespace(namespace)
                    .list()
                    .getItems()
                    .stream()
                    .filter(e -> e.getInvolvedObject() != null &&
                                 e.getInvolvedObject().getApiVersion() != null &&
                                 e.getInvolvedObject().getApiVersion().contains("strimzi"))
                    .sorted((a, b) -> {
                        String ta = a.getLastTimestamp() != null ? a.getLastTimestamp() : "";
                        String tb = b.getLastTimestamp() != null ? b.getLastTimestamp() : "";
                        return tb.compareTo(ta);
                    })
                    .collect(Collectors.toList());

            if (events.isEmpty()) {
                return "No Kafka-related events found in namespace: " + namespace;
            }

            return events.stream()
                    .map(e -> String.format("[%s] %s/%s — %s: %s",
                            e.getLastTimestamp(),
                            e.getInvolvedObject().getKind(),
                            e.getInvolvedObject().getName(),
                            e.getReason(),
                            e.getMessage()))
                    .collect(Collectors.joining("\n"));
        } catch (Exception e) {
            LOG.errorf(e, "Failed to get events in namespace %s", namespace);
            return "ERROR getting events: " + e.getMessage();
        }
    }

    @Tool("List all namespaces in the cluster that contain Kafka clusters. Use when the user does not specify a namespace.")
    public String listNamespacesWithKafka() {
        try {
            ResourceDefinitionContext ctx = kafkaContext("kafkas");
            GenericKubernetesResourceList list = client
                    .genericKubernetesResources(ctx)
                    .inAnyNamespace()
                    .list();

            if (list.getItems().isEmpty()) {
                return "No Kafka clusters found in any namespace.";
            }

            return list.getItems().stream()
                    .map(r -> String.format("namespace=%-30s name=%s",
                            r.getMetadata().getNamespace(),
                            r.getMetadata().getName()))
                    .collect(Collectors.joining("\n"));
        } catch (Exception e) {
            LOG.errorf(e, "Failed to list Kafka clusters across namespaces");
            return "ERROR listing namespaces with Kafka: " + e.getMessage();
        }
    }

    // ----------------------------------------------------------------
    // Internal helpers
    // ----------------------------------------------------------------

    private String listStrimziResources(String namespace, String plural, String kind) {
        try {
            ResourceDefinitionContext ctx = kafkaContext(plural);
            GenericKubernetesResourceList list = client
                    .genericKubernetesResources(ctx)
                    .inNamespace(namespace)
                    .list();

            if (list.getItems().isEmpty()) {
                return "No " + kind + " resources found in namespace: " + namespace;
            }

            return list.getItems().stream()
                    .map(r -> formatResource(r, kind))
                    .collect(Collectors.joining("\n---\n"));

        } catch (Exception e) {
            LOG.errorf(e, "Failed to list %s in namespace %s", kind, namespace);
            return "ERROR listing " + kind + " resources: " + e.getMessage();
        }
    }

    private String formatResource(GenericKubernetesResource r, String kind) {
        StringBuilder sb = new StringBuilder();
        sb.append("kind: ").append(kind).append("\n");
        sb.append("name: ").append(r.getMetadata().getName()).append("\n");
        sb.append("namespace: ").append(r.getMetadata().getNamespace()).append("\n");

        // Extract conditions from status if present
        Object status = r.getAdditionalProperties().get("status");
        if (status instanceof java.util.Map) {
            Object conditions = ((java.util.Map<?, ?>) status).get("conditions");
            if (conditions instanceof List) {
                sb.append("conditions:\n");
                ((List<?>) conditions).forEach(c -> {
                    if (c instanceof java.util.Map) {
                        java.util.Map<?, ?> cond = (java.util.Map<?, ?>) c;
                        sb.append("  - type=").append(cond.get("type"))
                          .append(" status=").append(cond.get("status"))
                          .append(" reason=").append(cond.get("reason")).append("\n");
                    }
                });
            }
        }

        // Include spec summary
        Object spec = r.getAdditionalProperties().get("spec");
        if (spec instanceof java.util.Map) {
            java.util.Map<?, ?> specMap = (java.util.Map<?, ?>) spec;
            if (specMap.containsKey("replicas")) {
                sb.append("replicas: ").append(specMap.get("replicas")).append("\n");
            }
        }

        return sb.toString();
    }

    private ResourceDefinitionContext kafkaContext(String plural) {
        return new ResourceDefinitionContext.Builder()
                .withGroup(STRIMZI_GROUP)
                .withVersion(STRIMZI_VERSION)
                .withPlural(plural)
                .withNamespaced(true)
                .build();
    }
}