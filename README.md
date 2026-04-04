# Kafka Diagnostic Agent

> AI-powered Kafka diagnostic agent for Red Hat AMQ Streams on OCP.
> Analyzes Kafka clusters using natural language, combining live cluster
> inspection via the Kubernetes API with RAG over official documentation.

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

## How it works

The agent receives a natural language question and operates in two modes:

**Live cluster mode** — queries Kafka CRDs, pods, events, and logs via the
Kubernetes API (no `oc` binary required), then enriches with RAG documentation.

**Report mode** — accepts a Strimzi `report.sh` ZIP file uploaded via the Web UI,
extracts all YAML/text files, and diagnoses without direct cluster access.

In both modes the agent:
1. Gathers cluster data (live or from ZIP)
2. Searches the ChromaDB knowledge base for relevant documentation chunks
3. Returns a structured diagnosis: Summary → Findings → Documentation Context → Recommendations

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
│       └── tools/                ← KubernetesTool, RAGQueryTool, ReportUploadTool, ...
└── docs/                         ← Per-phase deployment and implementation guides
    ├── phase-1-infrastructure.md
    ├── phase-2-quarkus-app.md
    ├── phase-3-rag.md
    └── phase-4-web-ui.md
```

---

## Deployment phases

| Phase | Status | Description |
|-------|--------|-------------|
| [Phase 1](docs/phase-1-infrastructure.md) | ✅ Complete | Base infrastructure: models, ChromaDB, RBAC |
| [Phase 2](docs/phase-2-quarkus-app.md) | ✅ Complete | Quarkus app + Kubernetes API tools |
| [Phase 3](docs/phase-3-rag.md) | ✅ Complete | RAG over documentation PDFs + SHA-256 incremental indexing |
| [Phase 4](docs/phase-4-web-ui.md) | ✅ Complete | Web UI v2 + Strimzi report.sh ZIP upload + RAG citation |
| Phase 5 | Pending | KCS API integration + dynamic PDF watcher |
| Phase 6 | Pending | Debezium diagnostics + final polish |

---

## Example queries

**Live cluster mode:**
```
give me a brief status of the current cluster
why is mirrormaker not working
why do we have consumer lag
analyze any issues in my environment
how can I tune kafka for better performance
```

**Report mode (upload a Strimzi report.sh ZIP):**
```
analyze the uploaded report and summarize all findings
what issues do you see in the kafka cluster
check the kafka events for errors and warnings
show me the kafka cluster configuration
```

---

## Key lessons learned

- **RTX 5060 Ti (CUDA 13.0)**: requires `rhaiis/vllm-cuda-rhel9:3.3.0`
- **vLLM port**: changed to `8000` in RHOAI 3.3
- **Qwen3 tool calling**: requires `--enable-auto-tool-choice --tool-call-parser=hermes`
- **KServe headless service**: does not work for embeddings — create a separate ClusterIP Service
- **ChromaDB 1.0.0**: requires full tenant/database path in all API v2 routes
- **PDFBox 3.x**: `PDDocument.load()` removed — use `Loader.loadPDF(new RandomAccessReadBufferedFile(file))`
- **Java virtual threads + HttpClient**: use `HttpURLConnection` instead
- **ThreadLocal across requests**: doesn't work — combine upload + diagnose in single request
- **max-model-len**: must be increased to 14000 for multi-tool-call sessions (`--gpu-memory-utilization=0.90`)
- **Stale ReplicaSets**: after patching ServingRuntime, scale the correct RS manually if pod keeps old args