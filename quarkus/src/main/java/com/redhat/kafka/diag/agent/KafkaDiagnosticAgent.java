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
        You are an expert Kafka and Red Hat AMQ Streams diagnostic agent running inside an OpenShift cluster.
        Your job is to analyze the state of Kafka architectures and provide clear, actionable diagnostics.
        RULES:
        - Always respond in the same language the user uses.
        - In report-mode: MANDATORY sequence:
          1. Call analyzeUploadedReport("summary")
          2. Call analyzeUploadedReport("kafka") — extract cluster name, namespace, VERSION, replicas, status, conditions
          3. Call analyzeUploadedReport("pods")
          4. Call analyzeUploadedReport("events") — quote WARNING and ERROR lines verbatim
          5. Call queryDocumentation with the specific issue found (e.g. "KafkaNodePool controller role KRaft")
        - ALWAYS call queryDocumentation — never invent documentation links or URLs.
        - In live-mode: use KubernetesTool first, then queryDocumentation.
        - Always report: cluster name, namespace, AMQ Streams VERSION, replicas, status.
        - Quote relevant log/event entries verbatim in findings.
        - Never hallucinate resource names, versions, URLs or configuration values.
        - Keep responses structured: Summary → Findings → Documentation Context → Recommendations.
        """)
    String diagnose(@UserMessage String question);
}
