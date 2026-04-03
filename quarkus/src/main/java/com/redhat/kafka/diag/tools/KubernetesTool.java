package com.redhat.kafka.diag.tools;

import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@ApplicationScoped
public class KubernetesTool {

    private static final Logger LOG = Logger.getLogger(KubernetesTool.class);
    private static final int TIMEOUT_SECONDS = 30;

    @Tool("Get all Kafka clusters in a namespace. Returns YAML with cluster status, replicas, and conditions.")
    public String getKafkaClusters(String namespace) {
        return runOc("get", "kafka", "-n", namespace, "-o", "yaml");
    }

    @Tool("Get all KafkaTopics in a namespace with their configuration and status.")
    public String getKafkaTopics(String namespace) {
        return runOc("get", "kafkatopic", "-n", namespace, "-o", "wide");
    }

    @Tool("Get all KafkaUsers in a namespace.")
    public String getKafkaUsers(String namespace) {
        return runOc("get", "kafkauser", "-n", namespace, "-o", "wide");
    }

    @Tool("Get KafkaConnect and KafkaConnector resources in a namespace with their status.")
    public String getKafkaConnect(String namespace) {
        return runOc("get", "kafkaconnect,kafkaconnector", "-n", namespace, "-o", "yaml");
    }

    @Tool("Get MirrorMaker2 resources in a namespace with full YAML including status and conditions.")
    public String getMirrorMaker2(String namespace) {
        return runOc("get", "kafkamirrormaker2", "-n", namespace, "-o", "yaml");
    }

    @Tool("Get KafkaBridge resources in a namespace.")
    public String getKafkaBridge(String namespace) {
        return runOc("get", "kafkabridge", "-n", namespace, "-o", "yaml");
    }

    @Tool("Get all events in a namespace filtered by Kafka-related resources. Useful to spot errors and warnings.")
    public String getKafkaEvents(String namespace) {
        return runOc("get", "events", "-n", namespace,
                "--field-selector", "involvedObject.apiVersion=kafka.strimzi.io/v1beta2",
                "--sort-by", ".lastTimestamp");
    }

    @Tool("Get all pods in a namespace with their status. Useful to check if Kafka broker/zookeeper pods are running.")
    public String getPods(String namespace) {
        return runOc("get", "pods", "-n", namespace, "-o", "wide");
    }

    @Tool("Get logs of a specific pod in a namespace. Use to inspect broker errors, connector failures, etc.")
    public String getPodLogs(String namespace, String podName) {
        return runOc("logs", podName, "-n", namespace, "--tail=100");
    }

    @Tool("Describe a specific Kafka resource to get full details including status conditions and events.")
    public String describeKafkaResource(String namespace, String resourceType, String resourceName) {
        return runOc("describe", resourceType, resourceName, "-n", namespace);
    }

    @Tool("List all namespaces in the cluster that contain Kafka resources. Useful when the user does not specify a namespace.")
    public String listNamespacesWithKafka() {
        return runOc("get", "kafka", "--all-namespaces", "-o",
                "custom-columns=NAMESPACE:.metadata.namespace,NAME:.metadata.name,READY:.status.conditions[0].status");
    }

    // ----------------------------------------------------------------
    // Internal executor
    // ----------------------------------------------------------------
    private String runOc(String... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add("oc");
        for (String arg : args) {
            cmd.add(arg);
        }

        LOG.debugf("Running: %s", String.join(" ", cmd));

        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return "ERROR: command timed out after " + TIMEOUT_SECONDS + "s: " + String.join(" ", cmd);
            }

            String output = new BufferedReader(new InputStreamReader(process.getInputStream()))
                    .lines()
                    .collect(Collectors.joining("\n"));

            if (output.isBlank()) {
                return "No resources found in the specified namespace.";
            }

            return output;

        } catch (Exception e) {
            LOG.errorf(e, "Failed to run oc command: %s", String.join(" ", cmd));
            return "ERROR running oc command: " + e.getMessage();
        }
    }
}
