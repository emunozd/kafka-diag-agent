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

PDFs are stored in a PVC with this structure:

```
/pdfdata/
├── streams/
│   └── 3.1/
│       └── *.pdf
└── debezium/
    └── 3.2.7/
        └── *.pdf
```

---

## Pre-requisites

Before running `helm install` make sure you have the following:

### 1. OpenShift cluster with RHOAI 3.3

- OCP 4.x cluster with RHOAI 3.3 operator installed
- At least one GPU node with CUDA support (tested on RTX 5060 Ti 16GB)
- `oc` CLI installed and logged in as cluster-admin or with sufficient permissions

### 2. Helm 3

```bash
# macOS
brew install helm

# Linux
curl https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3 | bash
```

### 3. RHOAI ServingRuntime for vLLM

The Helm chart deploys InferenceServices that require a vLLM ServingRuntime.
Apply it before running helm install:

```bash
oc apply -f helm/templates/servingruntimes.yaml
```

### 4. HuggingFace token (required for local GPU mode)

The LLM and embedding models are pulled from HuggingFace.
Get your token at https://huggingface.co/settings/tokens

### 5. Red Hat KCS offline token (optional)

Enables real-time search of the Red Hat Knowledge Base.
Get your token at https://access.redhat.com/management/api

### 6. Documentation PDFs

Download the official Red Hat documentation PDFs and place them in the correct
folder structure. They will be uploaded to the PVC after deployment.

**Red Hat Streams for Apache Kafka 3.1:**
https://docs.redhat.com/en/documentation/red_hat_streams_for_apache_kafka/3.1

**Red Hat build of Debezium 3.2.7:**
https://docs.redhat.com/en/documentation/red_hat_build_of_debezium/3.2.7

---

## Installation

### Option A — Local GPU on OCP (RHOAI 3.3)

```bash
# Clone the repo
git clone https://github.com/emunozd/kafka-diag-agent
cd kafka-diag-agent

# Install — replace <your-hf-token> with your HuggingFace token
helm install kafka-diag ./helm/ \
  --set huggingface.token=<your-hf-token>

# Optional: enable KCS Knowledge Base search
helm install kafka-diag ./helm/ \
  --set huggingface.token=<your-hf-token> \
  --set kcs.offlineToken=<your-rh-offline-token>
```

### Option B — External LLM (Claude, OpenAI, etc.)

```bash
helm install kafka-diag ./helm/ \
  -f helm/values-external-llm.yaml \
  --set llm.external.baseUrl=https://api.anthropic.com/v1 \
  --set llm.external.apiKey=<your-api-key> \
  --set llm.external.modelName=claude-sonnet-4-6
```

---

## Post-installation steps

### 1. Wait for all pods to be ready

```bash
oc get pods -n kafka-diag-agent -w
```

Expected state:

```
chromadb-xxx                  1/1     Running
kafka-diag-app-xxx            1/1     Running
nomic-embed-predictor-xxx     1/1     Running
qwen3-8b-llm-predictor-xxx    1/1     Running
```

Note: the `kafka-diag-app` pod will not start until ChromaDB and the LLM
are ready — this is enforced by `initContainers` in the Deployment.

### 2. Upload documentation PDFs

The PDF PVC is mounted read-only in the app pod. Use the ChromaDB pod to upload:

```bash
CHROMA_POD=$(oc get pod -n kafka-diag-agent -l app=chromadb -o jsonpath='{.items[0].metadata.name}')

# Create folder structure
oc exec -n kafka-diag-agent $CHROMA_POD -- mkdir -p /pdfdata/streams/3.1
oc exec -n kafka-diag-agent $CHROMA_POD -- mkdir -p /pdfdata/debezium/3.2.7

# Upload Streams PDFs — adjust local path to where you downloaded the PDFs
for pdf in /opt/disk/streams_docs/*.pdf; do
  oc cp "$pdf" kafka-diag-agent/$CHROMA_POD:/pdfdata/streams/3.1/
done

# Upload Debezium PDFs — adjust local path to where you downloaded the PDFs
for pdf in /opt/disk/debezium_docs/*.pdf; do
  oc cp "$pdf" kafka-diag-agent/$CHROMA_POD:/pdfdata/debezium/3.2.7/
done

# Verify structure
oc exec -n kafka-diag-agent $CHROMA_POD -- find /pdfdata -name "*.pdf" | sort
```

### 3. Wait for PDF indexing to complete

The PDF Watcher indexes new PDFs automatically. Monitor progress:

```bash
oc logs -f -n kafka-diag-agent deployment/kafka-diag-app | grep -E "indexed|skipped|failed|complete"
```

Expected output when done (~25 min for 22 PDFs):

```
PDF indexing complete — indexed=22 skipped=0 failed=0
```

### 4. Access the Web UI

```bash
oc get route kafka-diag-app -n kafka-diag-agent
```

Open the URL in your browser.

---

## Upgrade

```bash
helm upgrade kafka-diag ./helm/ \
  --set huggingface.token=<your-hf-token>
```

## Uninstall

```bash
helm uninstall kafka-diag
oc delete namespace kafka-diag-agent

# ClusterRole and ClusterRoleBinding are cluster-scoped and are not
# deleted when the namespace is removed — delete them manually
oc delete clusterrole kafka-diag-reader --ignore-not-found
oc delete clusterrolebinding kafka-diag-reader-binding --ignore-not-found
```

Note: from the current chart version these resources include Helm annotations
and `helm uninstall` will delete them automatically.
Manual deletion only applies if you are coming from a previous installation.

---

## Repository structure

```
kafka-diag-agent/
├── helm/                         ← Helm chart (full infrastructure)
│   ├── Chart.yaml
│   ├── values.yaml
│   ├── values-external-llm.yaml
│   └── templates/
│       ├── namespace.yaml
│       ├── rbac.yaml
│       ├── serviceaccount.yaml
│       ├── secrets.yaml
│       ├── pvcs.yaml
│       ├── servingruntimes.yaml
│       ├── inferenceservices.yaml
│       ├── chromadb.yaml
│       └── quarkus-app.yaml
├── quarkus/                      ← Java app (Quarkus + LangChain4j)
│   ├── pom.xml
│   └── src/main/java/com/redhat/kafka/diag/
│       ├── agent/                ← KafkaDiagnosticAgent
│       ├── config/               ← AgentConfig
│       ├── rag/                  ← EmbeddingClient, ChromaDBClient, PDFIndexer, PDFWatcher
│       ├── resource/             ← DiagnosticResource (REST endpoints)
│       └── tools/                ← KubernetesTool, RAGQueryTool, KCSSearchTool,
│                                    ReportUploadTool, DebeziumTool, StrimziReportTool
└── docs/
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
| [Phase 4](docs/phase-4-web-ui.md) | ✅ Complete | Web UI v2 + Strimzi report.sh ZIP upload |
| [Phase 5](docs/phase-5-kcs-pdf-watcher.md) | ✅ Complete | KCS API integration + dynamic PDF watcher |
| [Phase 6](docs/phase-6-debezium.md) | ✅ Complete | Debezium CDC diagnostics + Debezium docs |

---

## Example queries

**Live cluster mode:**
```
what kind of issues we have
what's the current cluster resource usage
how to tune the cluster for better performance
give me a brief status of the current cluster
why do we have consumer lag
analyze any issues in my environment
check debezium connectors status and issues
```

**Report mode (upload a Strimzi report.sh ZIP):**
```
analyze the uploaded report and summarize all findings
analyze kafka connect and debezium connectors status and configuration
what issues do you see in the kafka cluster
check the kafka events for errors and warnings
```

To generate a Strimzi report:

```bash
curl -s https://raw.githubusercontent.com/strimzi/strimzi-kafka-operator/main/tools/report.sh \
  | bash -s -- \
    --namespace=<kafka-namespace> \
    --cluster=<kafka-cluster-name> \
    --connect=<connect-cluster-name>   # optional, for KafkaConnect/Debezium
```

---

## Key lessons learned

- **RTX 5060 Ti (CUDA 13.0)**: requires `rhaiis/vllm-cuda-rhel9:3.3.0`
- **vLLM port**: changed to `8000` in RHOAI 3.3
- **Qwen3 tool calling**: requires `--enable-auto-tool-choice --tool-call-parser=hermes`
- **max-model-len**: must be 14000+ for multi-tool sessions
- **KServe headless service**: does not work for embeddings — create a separate ClusterIP Service
- **ChromaDB 1.0.0**: requires full tenant/database path in all API v2 routes
- **PDFBox 3.x**: use `Loader.loadPDF(new RandomAccessReadBufferedFile(file))`
- **Java virtual threads + HttpClient**: use `HttpURLConnection` instead
- **KCS deprecated API**: `/rs/cases/solutions` → use `/support/search/kcs?fq=documentKind:Solution`
- **PDF watcher delay**: use `10×` interval as startup delay to avoid competing with initial indexing
- **PVC read-only**: app pod mounts PDF PVC as read-only — use ChromaDB pod for file operations
- **Race condition**: use `initContainers` to wait for ChromaDB and LLM before starting the app
- **LangChain4j memory isolation**: use `@MemoryId UUID` per agent call for two-phase diagnosis
- **Qute escaping**: escape `{` in YAML content with `\{` to prevent template engine errors
- **Stale ReplicaSets**: after patching InferenceService, delete old RS and pods manually

---

## Troubleshooting

### Changing vLLM parameters (gpu-memory-utilization, max-model-len, etc.)

Parameters like `--gpu-memory-utilization` and `--max-model-len` live in the
`ServingRuntime`, not in the `InferenceService`. To change them:

**Step 1 — Patch the ServingRuntime:**
```bash
oc patch servingruntimes vllm-runtime-llm -n kafka-diag-agent --type=json -p='[
  {"op": "replace", "path": "/spec/containers/0/args", "value": [
    "--model=/mnt/models",
    "--dtype=auto",
    "--max-model-len=14000",
    "--gpu-memory-utilization=0.80",
    "--max-num-seqs=4",
    "--enable-auto-tool-choice",
    "--tool-call-parser=hermes"
  ]}
]'
```

**Step 2 — Delete the current pod:**
```bash
oc delete pod -n kafka-diag-agent -l serving.kserve.io/inferenceservice=qwen3-8b-llm --force --grace-period=0
```

**Step 3 — RHOAI will recreate stale ReplicaSets. Delete them:**
```bash
# Find all RS with 0 desired replicas and delete them
oc get rs -n kafka-diag-agent | grep qwen3 | grep "0         0         0" | awk '{print $1}' | xargs oc delete rs -n kafka-diag-agent
```

**Step 4 — Verify the new pod has the correct args:**
```bash
oc describe pod -n kafka-diag-agent -l serving.kserve.io/inferenceservice=qwen3-8b-llm | grep -A15 "Args"
```

To make the change permanent, update `helm/values.yaml` and commit:
```yaml
llm:
  gpu:
    memoryUtilization: "0.80"
    maxModelLen: "14000"
```

### ChromaDB Permission Denied on startup

If ChromaDB crashes with `Permission denied` on `/data`:

```bash
# Grant anyuid SCC to the service account
oc adm policy add-scc-to-user anyuid -z kafka-diag-sa -n kafka-diag-agent

# Restart ChromaDB
oc rollout restart deployment/chromadb -n kafka-diag-agent
```

This is handled automatically by the Helm chart via `serviceaccount.yaml`.
The manual fix is only needed if the ClusterRoleBinding was not created.

### ClusterRole/ClusterRoleBinding errors on helm install

If you get `cannot be imported into the current release` errors:

```bash
oc delete clusterrole kafka-diag-reader --ignore-not-found
oc delete clusterrolebinding kafka-diag-reader-binding --ignore-not-found
oc delete clusterrolebinding kafka-diag-sa-anyuid --ignore-not-found
```

Then retry `helm install`.

### GPU out of memory (NVMLError_Unknown)

If vLLM crashes with GPU memory errors, reboot the host:
```bash
# On CRC
crc stop && crc start
```

Then reduce `gpu-memory-utilization` in the ServingRuntime (see above).