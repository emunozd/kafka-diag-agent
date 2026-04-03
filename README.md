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

The agent receives a natural language question and uses tool calling to:

1. **Inspect the live cluster** — queries Kafka CRDs, pods, events, and logs via the Kubernetes API (no `oc` binary required — uses the pod's ServiceAccount)
2. **Search the documentation** — queries ChromaDB for the most relevant chunks from the indexed Red Hat Streams PDFs
3. **Search KCS** — provides a direct link to relevant Red Hat Knowledge Base articles
4. **Synthesize** — combines all sources into a structured diagnosis: Summary → Findings → Recommendations

---

## Quick start

### Option A — Local GPU on OCP (RHOAI 3.3)

```bash
helm install kafka-diag ./helm/ \
  --set huggingface.token=hf_xxx
```

### Option B — Quarkus app only with external LLM (Claude, OpenAI, etc.)

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
│   ├── values.yaml               ← defaults (local GPU mode)
│   ├── values-external-llm.yaml
│   └── templates/
├── quarkus/                      ← Java app (Quarkus + LangChain4j)
│   ├── pom.xml
│   └── src/main/java/com/redhat/kafka/diag/
│       ├── agent/                ← KafkaDiagnosticAgent (LangChain4j AI service)
│       ├── config/               ← AgentConfig (typed config mapping)
│       ├── rag/                  ← EmbeddingClient, ChromaDBClient, PDFIndexer, PDFWatcher
│       ├── resource/             ← DiagnosticResource (REST endpoint)
│       └── tools/                ← KubernetesTool, RAGQueryTool, KCSSearchTool, ...
└── docs/                         ← Per-phase deployment and implementation guides
    ├── phase-1-infrastructure.md
    ├── phase-2-quarkus-app.md
    └── phase-3-rag.md
```

---

## Deployment phases

| Phase | Status | Description |
|-------|--------|-------------|
| [Phase 1](docs/phase-1-infrastructure.md) | ✅ Complete | Base infrastructure: models, ChromaDB, RBAC |
| [Phase 2](docs/phase-2-quarkus-app.md) | ✅ Complete | Quarkus app + Kubernetes API tools (no `oc` binary) |
| [Phase 3](docs/phase-3-rag.md) | ✅ Complete | RAG over documentation PDFs + SHA-256 incremental indexing |
| Phase 4 | Pending | Web UI v2 + report.sh upload + namespace override |
| Phase 5 | Pending | KCS API integration + dynamic PDF watcher |
| Phase 6 | Pending | Debezium diagnostics + final polish |

---

## Prerequisites

- OpenShift Container Platform (CRC is valid for development)
- RHOAI 3.3 installed
- NVIDIA GPU with CUDA 13.0+ (for Option A — tested on RTX 5060 Ti)
- Helm 3
- `oc` CLI authenticated
- HuggingFace token (for model downloads)

---

## Example queries

```
"give me a brief status of the current cluster"
"why is mirrormaker not working"
"how is my current kafka architecture"
"how can I tune kafka for better performance and throughput"
"why do we have consumer lag"
"analyze any issues in my environment"
"analyze my kafka cluster in namespace my-ns"
```

---

## Key lessons learned

- **RTX 5060 Ti (CUDA 13.0)**: requires `rhaiis/vllm-cuda-rhel9:3.3.0` — earlier versions fail with Error 803
- **vLLM port**: changed to `8000` in RHOAI 3.3 (was `8080`)
- **Qwen3 tool calling**: requires `--enable-auto-tool-choice --tool-call-parser=hermes` in the ServingRuntime
- **KServe headless service**: does not work for embeddings — create a separate ClusterIP Service on port 8000
- **ChromaDB 1.0.0**: requires full tenant/database path in all API v2 routes
- **No `oc` binary in pods**: use Fabric8 `OpenShiftClient` injected via CDI instead
- **PDFBox 3.x**: `PDDocument.load()` removed — use `Loader.loadPDF(new RandomAccessReadBufferedFile(file))`
- **Java virtual threads + HttpClient**: use `HttpURLConnection` instead to avoid body not being sent