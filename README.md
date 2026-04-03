# Kafka Diagnostic Agent

> AI-powered Kafka diagnostic agent for Red Hat AMQ Streams on OCP. Analyzes Kafka clusters using natural language, RAG over official docs, and live cluster inspection via Strimzi tools.

---

## Stack

| Componente | Tecnología |
|-----------|-----------|
| LLM | `RedHatAI/Qwen3-8B-FP8-dynamic` via vLLM (RHOAI 3.3) |
| Embeddings | `RedHatAI/nomic-embed-text-v1.5` via vLLM CPU |
| Vector store | ChromaDB |
| App framework | Quarkus 3.x + LangChain4j |
| Plataforma | OpenShift Container Platform + RHOAI 3.3 |
| Despliegue | Helm 3 |

---

## Despliegue rápido

### Opción A — GPU local en OCP (RHOAI 3.3)

```bash
helm install kafka-diag ./helm/ \
  --set huggingface.token=hf_xxx
```

### Opción B — Solo app Quarkus con LLM externo (Claude, OpenAI, etc.)

```bash
helm install kafka-diag ./helm/ \
  --set llm.local=false \
  --set llm.external.baseUrl=https://api.anthropic.com/v1 \
  --set llm.external.apiKey=sk-ant-xxx \
  --set llm.external.modelName=claude-sonnet-4-6
```

---

## Estructura del repositorio

```
kafka-diag-agent/
├── helm/                    ← Helm chart completo
│   ├── Chart.yaml
│   ├── values.yaml          ← valores por defecto (modo local GPU)
│   ├── values-external-llm.yaml
│   ├── values-no-gpu.yaml
│   └── templates/
├── quarkus/                 ← App Java (Quarkus + LangChain4j)
│   ├── pom.xml
│   └── src/
└── docs/                    ← Guías de despliegue por fase
    ├── Fase1.md
    ├── Fase2.md
    └── ...
```

---

## Documentación de despliegue por fases

| Fase | Descripción |
|------|-------------|
| [Fase 1](docs/Fase1.md) | Infraestructura base: modelos, ChromaDB, RBAC |
| [Fase 2](docs/Fase2.md) | App Quarkus + tools básicos (oc, Strimzi report) |
| [Fase 3](docs/Fase3.md) | RAG con PDFs de documentación |
| [Fase 4](docs/Fase4.md) | Web UI v2 + upload de report.sh + namespace override |
| [Fase 5](docs/Fase5.md) | KCS Tool + PDF watcher dinámico |
| [Fase 6](docs/Fase6.md) | Debezium placeholder + pulido final |

---

## Prerrequisitos

- OpenShift Container Platform (CRC válido para desarrollo)
- RHOAI 3.3 instalado
- GPU NVIDIA con CUDA 13.0+ (para Opción A)
- Helm 3
- `oc` CLI autenticado
- Token de HuggingFace (para descarga de modelos)
