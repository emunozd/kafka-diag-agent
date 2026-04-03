package com.redhat.kafka.diag.resource;

import com.redhat.kafka.diag.agent.KafkaDiagnosticAgent;
import com.redhat.kafka.diag.config.AgentConfig;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

/**
 * REST endpoint for the Kafka Diagnostic Agent.
 *
 * POST /api/diagnose — receives a natural language question and optional
 * namespace override, delegates to the LangChain4j agent, strips any
 * residual Qwen3 thinking blocks, and returns the diagnosis.
 */
@Path("/api")
public class DiagnosticResource {

    private static final Logger LOG = Logger.getLogger(DiagnosticResource.class);

    @Inject
    KafkaDiagnosticAgent agent;

    @Inject
    AgentConfig config;

    /**
     * Main diagnostic endpoint.
     *
     * If no namespace is provided in the request, the app's own namespace
     * (injected via APP_NAMESPACE env var) is used as default.
     *
     * The namespace is prepended to the question so the agent always
     * knows which namespace to target in tool calls:
     *   "[namespace: my-ns] why is mirrormaker not working"
     */
    @POST
    @Path("/diagnose")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response diagnose(DiagnoseRequest request) {
        if (request == null || request.question() == null || request.question().isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ErrorResponse("question is required"))
                    .build();
        }

        String namespace = (request.namespace() != null && !request.namespace().isBlank())
                ? request.namespace()
                : config.defaultNamespace();

        // Prepend namespace context so the agent uses it in all tool calls
        String question = String.format("[namespace: %s] %s", namespace, request.question());

        LOG.infof("Diagnosing: namespace=%s question=%s", namespace, request.question());

        try {
            String answer = agent.diagnose(question);
            // Strip any residual <think>...</think> blocks from Qwen3 thinking mode
            answer = stripThinkBlocks(answer);
            return Response.ok(new DiagnoseResponse(answer, namespace)).build();
        } catch (Exception e) {
            LOG.errorf(e, "Error during diagnosis");
            return Response.serverError()
                    .entity(new ErrorResponse("Diagnosis failed: " + e.getMessage()))
                    .build();
        }
    }

    /**
     * Health check endpoint.
     */
    @GET
    @Path("/health")
    @Produces(MediaType.APPLICATION_JSON)
    public Response health() {
        return Response.ok("{\"status\":\"ok\",\"agent\":\"kafka-diag-agent\"}").build();
    }

    /**
     * Removes Qwen3 thinking blocks from the response.
     * The system prompt uses /no_think to prevent them, but this is a
     * safety net in case any residual blocks slip through.
     */
    private String stripThinkBlocks(String text) {
        if (text == null) return "";
        return text.replaceAll("(?s)<think>.*?</think>", "").trim();
    }

    // ----------------------------------------------------------------
    // Request / Response records
    // ----------------------------------------------------------------

    public record DiagnoseRequest(String question, String namespace) {}

    public record DiagnoseResponse(String answer, String namespace) {}

    public record ErrorResponse(String error) {}
}
