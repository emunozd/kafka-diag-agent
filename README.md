# Kafka Diagnostic Agent

> AI-powered Kafka and Debezium CDC diagnostic agent for Red Hat AMQ Streams on OCP.
> Analyzes Kafka clusters and Debezium connectors using natural language, combining
> live cluster inspection via the Kubernetes API with RAG over official documentation
> and real-time KCS Knowledge Base search.

---

## Stack

| Component | Technology |
|-----------|-----------|
| LLM | `RedHatAI/Qwen3-8B-FP8-dynamic` via vLLM (RHOAI 3.3) |
| Embeddings | `RedHatAI/nomic-embed-text-v1.5` via vLLM CPU |
| Vector store | ChromaDB 1.0.0 |
| App framework | Quarkus 3.15.1 + LangChain4j 1.8.4 |
| Kubernetes client | Fabric8 (via `quarkus-openshift-client`) |
| PDF extraction | Apache PDFBox 3.0.3 |
| Java | 21 (via `ubi9/openjdk-21`) |
| Platform | OpenShift Container Platform + RHOAI 3.3 |
| Deployment | Helm 3 |

---

## Knowledge base

| Product | Version | PDFs |
|---------|---------|------|
| Red Hat Streams for Apache Kafka | 3.1 | 18 documents |
| Red Hat build of Debezium | 3.2.7 | 4 documents |

---

## How it works

The agent receives a natural language question and operates in two modes:

**Live cluster mode** — queries Kafka CRDs, pods, events, logs, and Debezium
connectors via the Kubernetes API (no `oc` binary required), then enriches
with RAG documentation and KCS articles.

**Report mode** — accepts a Strimzi `report.sh` ZIP file uploaded via the Web UI,
extracts all YAML/text files, and diagnoses without direct cluster access.
Supports reports generated with `--connect` flag for KafkaConnect/Debezium analysis.

In both modes the agent:
1. Gathers cluster data (live or from ZIP)
2. Searches ChromaDB for relevant documentation chunks (Streams + Debezium)
3. Searches the Red Hat KCS Knowledge Base for known solutions
4. Returns: Summary → Findings → Documentation Context → Recommendations → KCS Articles

---

## Quick start

### Option A — Local GPU on OCP (RHOAI 3.3)

```bash
helm install kafka-diag ./helm/ \
  --set huggingface.token=hf_xxx
```

### Option B — Quarkus app only with external LLM

```bash
helm install kafka-diag ./helm/ \
  --set llm.local=false \
  --set llm.external.baseUrl=https://api.anthropic.com/v1 \
  --set llm.external.apiKey=sk-ant-xxx \
  --set llm.external.modelName=claude-sonnet-4-6
```

### KCS integration (optional)

```bash
# Get your offline token at https://access.redhat.com/management/api
oc create secret generic rh-api-credentials \
  -n kafka-diag-agent \
  --from-literal=RH_OFFLINE_TOKEN=<your-offline-token>

oc rollout restart deployment/kafka-diag-app -n kafka-diag-agent
```

### Adding documentation PDFs dynamically

```bash
CHROMA_POD=$(oc get pod -n kafka-diag-agent -l app=chromadb -o jsonpath='{.items[0].metadata.name}')

# Create folder and copy PDFs
oc exec -n kafka-diag-agent $CHROMA_POD -- mkdir -p /pdfdata/streams/3.2
for pdf in /local/path/*.pdf; do
  oc cp "$pdf" kafka-diag-agent/$CHROMA_POD:/pdfdata/streams/3.2/
done
# PDF Watcher indexes automatically within 10 minutes
```

---

## Repository structure

```
kafka-diag-agent/
├── helm/                         ← Helm chart (full infrastructure)
│   ├── Chart.yaml
│   ├── values.yaml
│   ├── values-external-llm.yaml
│   └── templates/
├── quarkus/                      ← Java app (Quarkus + LangChain4j)
│   ├── pom.xml
│   └── src/main/java/com/redhat/kafka/diag/
│       ├── agent/                ← KafkaDiagnosticAgent (LangChain4j AI service)
│       ├── config/               ← AgentConfig
│       ├── rag/                  ← EmbeddingClient, ChromaDBClient, PDFIndexer, PDFWatcher
│       ├── resource/             ← DiagnosticResource (REST endpoints)
│       └── tools/                ← KubernetesTool, RAGQueryTool, KCSSearchTool,
│                                    ReportUploadTool, DebeziumTool, StrimziReportTool
└── docs/                         ← Per-phase deployment and implementation guides
    ├── phase-1-infrastructure.md
    ├── phase-2-quarkus-app.md
    ├── phase-3-rag.md
    ├── phase-4-web-ui.md
    ├── phase-5-kcs-pdf-watcher.md
    └── phase-6-debezium.md
```

---

## Deployment phases

| Phase | Status | Description |
|-------|--------|-------------|
| [Phase 1](docs/phase-1-infrastructure.md) | ✅ Complete | Base infrastructure: models, ChromaDB, RBAC |
| [Phase 2](docs/phase-2-quarkus-app.md) | ✅ Complete | Quarkus app + Kubernetes API tools |
| [Phase 3](docs/phase-3-rag.md) | ✅ Complete | RAG over documentation PDFs + SHA-256 incremental indexing |
| [Phase 4](docs/phase-4-web-ui.md) | ✅ Complete | Web UI v2 + Strimzi report.sh ZIP upload + RAG citation |
| [Phase 5](docs/phase-5-kcs-pdf-watcher.md) | ✅ Complete | KCS API integration + dynamic PDF watcher |
| [Phase 6](docs/phase-6-debezium.md) | ✅ Complete | Debezium CDC diagnostics + Debezium docs + folder reorganization |

---

## Example queries

**Live cluster mode:**
```
give me a brief status of the current cluster
why is mirrormaker not working
why do we have consumer lag
analyze any issues in my environment
how can I tune kafka for better performance
do I have any debezium connectors running
how do I configure a debezium postgresql connector
```

**Report mode (upload a Strimzi report.sh ZIP):**
```
analyze the uploaded report and summarize all findings
analyze the kafka connect and debezium connectors in this report
what issues do you see in the kafka cluster
check the kafka events for errors and warnings
```

---

## Key lessons learned

- **RTX 5060 Ti (CUDA 13.0)**: requires `rhaiis/vllm-cuda-rhel9:3.3.0`
- **vLLM port**: changed to `8000` in RHOAI 3.3
- **Qwen3 tool calling**: requires `--enable-auto-tool-choice --tool-call-parser=hermes`
- **max-model-len**: must be 14000+ for multi-tool sessions (`--gpu-memory-utilization=0.90`)
- **KServe headless service**: does not work for embeddings — create a separate ClusterIP Service
- **ChromaDB 1.0.0**: requires full tenant/database path in all API v2 routes
- **PDFBox 3.x**: use `Loader.loadPDF(new RandomAccessReadBufferedFile(file))`
- **Java virtual threads + HttpClient**: use `HttpURLConnection` instead
- **ThreadLocal across requests**: doesn't work — combine upload + diagnose in single request
- **Stale ReplicaSets**: after patching ServingRuntime, scale the correct RS manually
- **KCS deprecated API**: `/rs/cases/solutions` → use `/support/search/kcs?fq=documentKind:Solution`
- **Pre-fetch pattern**: inject RAG+KCS context into prompt directly — don't rely on model tool calls
- **ZIP path normalization**: handle both `report-DATE/reports/` and `reports/` prefixes
- **Issue extraction**: parse `conditions[].message` from Kafka YAML for specific RAG/KCS queries
- **PDF watcher delay**: use `10×` interval as startup delay to avoid competing with initial indexing
- **PVC read-only**: app pod mounts PDF PVC as read-only — use ChromaDB pod for file operations
- **Debezium tasks.max**: must always be `1` — Debezium does not support parallel tasks