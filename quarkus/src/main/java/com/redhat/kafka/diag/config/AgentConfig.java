package com.redhat.kafka.diag.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "kafka.diag")
public interface AgentConfig {

    Embed embed();
    Chroma chroma();
    Pdf pdf();
    Kcs kcs();

    @WithDefault("kafka-diag-agent")
    String defaultNamespace();

    interface Embed {
        @WithDefault("http://nomic-embed-svc.kafka-diag-agent.svc.cluster.local:8000/v1")
        String baseUrl();

        @WithDefault("/mnt/models")
        String modelName();
    }

    interface Chroma {
        @WithDefault("http://chromadb.kafka-diag-agent.svc.cluster.local:8000")
        String baseUrl();

        @WithDefault("kafka-knowledge")
        String collection();

        @WithDefault("5")
        int topK();
    }

    interface Pdf {
        @WithDefault("/pdfdata")
        String basePath();
    }

    interface Kcs {
        @WithDefault("")
        String offlineToken();

        @WithDefault("https://access.redhat.com/search/#/?q={query}&documentKind=Solution")
        String searchUrl();
    }
}
