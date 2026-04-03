package com.redhat.kafka.diag.resource;

import com.redhat.kafka.diag.agent.KafkaDiagnosticAgent;
import com.redhat.kafka.diag.config.AgentConfig;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

@Path("/api")
public class DiagnosticResource {

    private static final Logger LOG = Logger.getLogger(DiagnosticResource.class);

    @Inject
    KafkaDiagnosticAgent agent;

    @Inject
    AgentConfig config;

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

        String ns = (request.namespace() != null && !request.namespace().isBlank())
                ? request.namespace()
                : config.defaultNamespace();

        String question = String.format(
                "[namespace: %s] %s", ns, request.question()
        );

        LOG.infof("Diagnosing: namespace=%s question=%s", ns, request.question());

        try {
            String answer = agent.diagnose(question);
            // Strip any residual <think>...</think> blocks from Qwen3
            answer = stripThinkBlocks(answer);
            return Response.ok(new DiagnoseResponse(answer, ns)).build();
        } catch (Exception e) {
            LOG.errorf(e, "Error during diagnosis");
            return Response.serverError()
                    .entity(new ErrorResponse("Diagnosis failed: " + e.getMessage()))
                    .build();
        }
    }

    @GET
    @Path("/health")
    @Produces(MediaType.APPLICATION_JSON)
    public Response health() {
        return Response.ok("{\"status\":\"ok\",\"agent\":\"kafka-diag-agent\"}").build();
    }

    private String stripThinkBlocks(String text) {
        if (text == null) return "";
        return text.replaceAll("(?s)<think>.*?</think>", "").trim();
    }

    public record DiagnoseRequest(String question, String namespace) {}
    public record DiagnoseResponse(String answer, String namespace) {}
    public record ErrorResponse(String error) {}
}
