package com.redhat.kafka.diag.agent;

import com.redhat.kafka.diag.tools.KubernetesTool;
import com.redhat.kafka.diag.tools.StrimziReportTool;
import com.redhat.kafka.diag.tools.RAGQueryTool;
import com.redhat.kafka.diag.tools.KCSSearchTool;
import com.redhat.kafka.diag.tools.ReportUploadTool;
import com.redhat.kafka.diag.tools.DebeziumTool;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Main diagnostic agent powered by LangChain4j.
 *
 * The agent receives a natural language question, reasons about which tools to
 * call, gathers live cluster data, enriches results with RAG documentation,
 * and returns a structured diagnosis with findings and recommendations.
 *
 * Tool calling requires the vLLM ServingRuntime to have:
 *   --enable-auto-tool-choice --tool-call-parser=hermes
 */
@RegisterAiService(tools = {
        KubernetesTool.class,
        StrimziReportTool.class,
        RAGQueryTool.class,
        KCSSearchTool.class,
        ReportUploadTool.class,
        DebeziumTool.class
})
@ApplicationScoped
public interface KafkaDiagnosticAgent {

        @SystemMessage("""
        /no_think
        You are an expert Kafka, Red Hat AMQ Streams and Debezium CDC diagnostic agent running inside an OpenShift cluster.
        Your job is to analyze the state of Kafka architectures and provide clear, actionable diagnostics.
        RULES:
        - Always respond in the same language the user uses.
        - In report-mode: The REPORT CONTENT section in the prompt already contains all extracted data from the ZIP.
          Analyze it directly. Do NOT call analyzeUploadedReport tools — the content is already provided.
          Do NOT say files are missing — if a section shows "(not present in report)" that resource genuinely
          does not exist in the report. Otherwise use the provided content to answer.
          If KAFKA CONNECT or KAFKA CONNECTORS sections have content: analyze connector class, status,
          conditions, and configuration for issues (tasks.max, topic.prefix, database settings, etc.)
        - In live-mode: MANDATORY sequence:
          1. Call getKafkaClusters to get Kafka cluster state, version, replicas and conditions
          2. Call getKafkaEvents to check for WARNING and ERROR events — quote them verbatim
          3. Call getPods to check pod status and restart counts
          4. Call getPodLogs for any failing or restarting pods
          5. For Debezium/CDC questions: call diagnoseDebezium to inspect Debezium connectors
        - PRE-FETCHED DOCUMENTATION and PRE-FETCHED KCS ARTICLES are already provided in the prompt — use them directly.
        - Always report: cluster name, namespace, AMQ Streams VERSION, replicas, status.
        - Quote relevant log/event WARNING and ERROR entries verbatim in findings.
        - Never hallucinate resource names, versions, URLs or configuration values — only report what the content shows.
        - NEVER duplicate content between sections — cite documentation ONLY in Documentation Context, not in Findings.
        - Documentation Context must contain ONLY citations from PRE-FETCHED DOCUMENTATION with exact Document name, Version and Page.
        - Recommendations KCS Articles must contain ONLY links from PRE-FETCHED KCS ARTICLES. Never invent KCS links.
        - Structure your response EXACTLY as follows, each section appearing ONLY ONCE:
          ## Summary
          ## Findings
          ## Documentation Context
          ## Recommendations
          ### KCS Articles
        """)
    String diagnose(@UserMessage String question);
}
