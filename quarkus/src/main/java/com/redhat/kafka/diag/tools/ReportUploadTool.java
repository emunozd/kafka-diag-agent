package com.redhat.kafka.diag.tools;

import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

/**
 * Report Upload Tool — implementado en Fase 4.
 * Permite analizar un report.sh subido por el usuario sin necesitar
 * acceso directo al clúster.
 */
@ApplicationScoped
public class ReportUploadTool {

    private static final Logger LOG = Logger.getLogger(ReportUploadTool.class);

    // El contenido del reporte subido se almacena temporalmente por sesión
    private static final ThreadLocal<String> uploadedReport = new ThreadLocal<>();

    @Tool("Analyze a previously uploaded Strimzi report.sh output. " +
          "Use this when the user has uploaded a report file instead of connecting directly to the cluster.")
    public String analyzeUploadedReport(String aspect) {
        // TODO Fase 4: implementar análisis real del reporte subido
        String report = uploadedReport.get();
        if (report == null || report.isBlank()) {
            return "[No report has been uploaded yet. " +
                   "Please upload a Strimzi report.sh output file via the web interface.]";
        }
        return "[Report analysis not yet implemented — will be available in Fase 4]";
    }

    /**
     * Called by DiagnosticResource when the user uploads a report file.
     */
    public static void setUploadedReport(String content) {
        uploadedReport.set(content);
    }

    public static void clearUploadedReport() {
        uploadedReport.remove();
    }
}
