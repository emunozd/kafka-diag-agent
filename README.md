# Kafka Diagnostic Agent

> AI-powered Kafka diagnostic agent for Red Hat AMQ Streams on OCP. Analyzes Kafka clusters using natural language, RAG over official docs, and live cluster inspection via Strimzi tools.

---

## Stack

| Component | Technology |
|-----------|-----------|
| LLM | `RedHatAI/Qwen3-8B-FP8-dynamic` via vLLM (RHOAI 3.3) |
| Embeddings | `RedHatAI/nomic-embed-text-v1.5` via vLLM CPU |
| Vector store | ChromaDB |
| App framework | Quarkus 3.x + LangChain4j |
| Platform | OpenShift Container Platform + RHOAI 3.3 |
| Deployment | Helm 3 |

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
├── helm/                    ← Helm chart (full infrastructure)
│   ├── Chart.yaml
│   ├── values.yaml          ← defaults (local GPU mode)
│   ├── values-external-llm.yaml
│   └── templates/
├── quarkus/                 ← Java app (Quarkus + LangChain4j)
│   ├── pom.xml
│   └── src/
└── docs/                    ← Per-phase deployment guides
    ├── phase-1-infrastructure.md
    ├── phase-2-quarkus-app.md
    └── ...
```

---

## Deployment phases

| Phase | Description |
|-------|-------------|
| [Phase 1](docs/phase-1-infrastructure.md) | Base infrastructure: models, ChromaDB, RBAC |
| [Phase 2](docs/phase-2-quarkus-app.md) | Quarkus app + basic tools (oc, Strimzi report) |
| Phase 3 | RAG over documentation PDFs |
| Phase 4 | Web UI v2 + report.sh upload + namespace override |
| Phase 5 | KCS tool + dynamic PDF watcher |
| Phase 6 | Debezium placeholder + final polish |

---

## Prerequisites

- OpenShift Container Platform (CRC is valid for development)
- RHOAI 3.3 installed
- NVIDIA GPU with CUDA 13.0+ (for Option A)
- Helm 3
- `oc` CLI authenticated
- HuggingFace token (for model downloads)

---

## Example queries

```
"why is mirrormaker not working"
"how is my current kafka architecture"
"give me a brief status of the current cluster"
"how can message consumption be improved"
"why do we have consumer lag"
"analyze any issues in my environment"
"analyze my kafka cluster in namespace my-ns"
```
