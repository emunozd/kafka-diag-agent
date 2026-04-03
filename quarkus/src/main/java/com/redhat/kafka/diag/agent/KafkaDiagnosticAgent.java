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
            - Use your tools to gather real cluster data before answering.
            - Start by querying the Kubernetes/OCP tools to get the current state.
            - Use the RAG tool to enrich your analysis with official documentation.
            - Use the KCS tool to check for known issues matching the problem.
            - Be specific: include resource names, namespaces, error messages, and recommendations.
            - If the user specifies a namespace, always use that namespace in tool calls.
            - If no namespace is specified, use the default namespace provided in the context.
            - For Debezium-related questions, acknowledge the limitation and advise checking Kafka Connect logs manually.
            - Never hallucinate resource names or configuration values — only report what the tools return.
            - Keep responses structured: Summary → Findings → Recommendations.
            """)
    String diagnose(@UserMessage String question);
}
