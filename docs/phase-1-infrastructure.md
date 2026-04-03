# Phase 1 — Infrastructure Setup on OCP
## Kafka Diagnostic Agent — RHOAI 3.3 / CRC / RTX 5060 Ti

> **Validated stack**: RHOAI 3.3 · OCP CRC · NVIDIA RTX 5060 Ti · CUDA 13.0
> **Images**: `rhaiis/vllm-cuda-rhel9:3.3.0` (GPU) · `rhaiis/vllm-cpu-rhel9:3.3.0` (CPU)
> **Models**: `RedHatAI/Qwen3-8B-FP8-dynamic` (LLM) · `RedHatAI/nomic-embed-text-v1.5` (embeddings)

---

## Internal URLs (used in subsequent phases)

| Component | Internal URL | Port |
|-----------|-------------|------|
| LLM Qwen3-8B | `http://qwen3-8b-llm-predictor.kafka-diag-agent.svc.cluster.local` | 8000 |
| nomic embeddings | `http://nomic-embed-svc.kafka-diag-agent.svc.cluster.local` | 8000 |
| ChromaDB | `http://chromadb.kafka-diag-agent.svc.cluster.local` | 8000 |
| PDFs in ChromaDB pod | `/pdfdata` | — |
| PDFs Streams 3.1 | `/pdfdata/streams-3.1/` | — |
| PDFs Streams 3.2+ | `/pdfdata/streams-3.2/` (future) | — |

> **Note**: The model is referenced as `/mnt/models` in the `model` field of all vLLM requests.
> **Note**: ChromaDB uses `/api/v2/` — v1 is deprecated in recent versions.
> **Note**: Qwen3 has thinking mode active by default — `<think>` blocks may appear. Controlled via `/no_think` in the system prompt (Phase 2).
> **Note**: PDFs are organized by version subfolder. The indexer in Phase 3 scans recursively and stores the version as metadata in each RAG chunk.

---

## Step 0 — Verify CRC state

```bash
crc status
oc whoami
oc get nodes

# Verify GPU allocatable — should show nvidia.com/gpu: 1
oc describe node | grep -A 10 "Allocatable"

# Verify RHOAI 3.3 is installed
oc get csv -n redhat-ods-operator | grep rhods
# Expected: rhods-operator.3.3.0   Succeeded
```

> If GPU shows `0` in allocatable after a reboot, restart the device plugin:
> ```bash
> oc delete pod -n nvidia-gpu-operator \
>   $(oc get pods -n nvidia-gpu-operator | grep device-plugin | awk '{print $1}')
> ```

---

## Step 1 — Create the namespace

```bash
oc apply -f - <<'EOF'
apiVersion: v1
kind: Namespace
metadata:
  name: kafka-diag-agent
  labels:
    opendatahub.io/dashboard: "true"
    modelmesh-enabled: "false"
EOF

oc get namespace kafka-diag-agent
```

---

## Step 2 — Create PVCs

```bash
oc apply -f - <<'EOF'
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: pvc-pdf-knowledge
  namespace: kafka-diag-agent
spec:
  accessModes:
    - ReadWriteOnce
  resources:
    requests:
      storage: 5Gi
  storageClassName: crc-csi-hostpath-provisioner
---
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: pvc-vector-store
  namespace: kafka-diag-agent
spec:
  accessModes:
    - ReadWriteOnce
  resources:
    requests:
      storage: 2Gi
  storageClassName: crc-csi-hostpath-provisioner
EOF
```

Verify:
```bash
oc get pvc -n kafka-diag-agent
```

> **Note**: With `crc-csi-hostpath-provisioner`, PVCs remain `Pending` until a pod mounts them. This is expected — they bind automatically on first use.

---

## Step 3 — ServiceAccount and RBAC

```bash
oc apply -f - <<'EOF'
apiVersion: v1
kind: ServiceAccount
metadata:
  name: kafka-diag-sa
  namespace: kafka-diag-agent
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRole
metadata:
  name: kafka-diag-reader
rules:
  - apiGroups: ["kafka.strimzi.io"]
    resources:
      - kafkas
      - kafkatopics
      - kafkausers
      - kafkaconnects
      - kafkaconnectors
      - kafkamirrormaker2s
      - kafkabridges
      - kafkanodepools
    verbs: ["get", "list", "watch"]
  - apiGroups: [""]
    resources:
      - pods
      - pods/log
      - configmaps
      - events
      - services
      - endpoints
    verbs: ["get", "list"]
  - apiGroups: ["apps"]
    resources:
      - deployments
      - statefulsets
    verbs: ["get", "list"]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata:
  name: kafka-diag-reader-binding
subjects:
  - kind: ServiceAccount
    name: kafka-diag-sa
    namespace: kafka-diag-agent
roleRef:
  kind: ClusterRole
  name: kafka-diag-reader
  apiGroup: rbac.authorization.k8s.io
EOF
```

Verify:
```bash
oc get sa kafka-diag-sa -n kafka-diag-agent
oc get clusterrolebinding kafka-diag-reader-binding

# Smoke test — must return: yes
oc auth can-i list kafkas \
  --as=system:serviceaccount:kafka-diag-agent:kafka-diag-sa
```

Grant `anyuid` SCC to the SA (required for ChromaDB):
```bash
oc adm policy add-scc-to-user anyuid \
  -z kafka-diag-sa \
  -n kafka-diag-agent
```

---

## Step 4 — Secrets

### HuggingFace token (required to download models)

Generate at: https://huggingface.co/settings/tokens

```bash
oc create secret generic huggingface-secret \
  --from-literal=HF_TOKEN=hf_xxxxxxxxxxxxxxxxxxxx \
  -n kafka-diag-agent
```

### Red Hat API / KCS token (completely optional)

If not created, the app starts normally and uses KCS fallback mode.

```bash
# Only if you have an offline token from: https://access.redhat.com/management/api
oc create secret generic rh-api-credentials \
  --from-literal=RH_OFFLINE_TOKEN=your_token_here \
  -n kafka-diag-agent
```

### External LLM secret (optional — use Claude/OpenAI instead of local GPU)

```bash
# Example with Anthropic Claude
oc create secret generic llm-config \
  --from-literal=LLM_BASE_URL=https://api.anthropic.com/v1 \
  --from-literal=LLM_API_KEY=sk-ant-... \
  --from-literal=LLM_MODEL_NAME=claude-sonnet-4-6 \
  -n kafka-diag-agent
```

---

## Step 5 — ServingRuntime for LLM (GPU · CUDA 13.0)

> **Important**: Use `rhaiis/vllm-cuda-rhel9:3.3.0` — earlier versions (3.2.x) do not support CUDA 13.0 on the RTX 5060 Ti.
> Container port is `8000`, not `8080`.
> `--enable-auto-tool-choice` and `--tool-call-parser=hermes` are mandatory for Qwen3 tool calling.

```bash
oc apply -f - <<'EOF'
apiVersion: serving.kserve.io/v1alpha1
kind: ServingRuntime
metadata:
  name: vllm-runtime-llm
  namespace: kafka-diag-agent
  labels:
    opendatahub.io/dashboard: "true"
spec:
  annotations:
    prometheus.io/port: "8000"
    prometheus.io/scheme: http
  containers:
    - args:
        - --model=/mnt/models
        - --dtype=auto
        - --max-model-len=4096
        - --gpu-memory-utilization=0.70
        - --max-num-seqs=4
        - --enable-auto-tool-choice
        - --tool-call-parser=hermes
      image: registry.redhat.io/rhaiis/vllm-cuda-rhel9:3.3.0
      name: kserve-container
      ports:
        - containerPort: 8000
          name: h2c
          protocol: TCP
      resources:
        limits:
          nvidia.com/gpu: "1"
        requests:
          nvidia.com/gpu: "1"
      volumeMounts:
        - mountPath: /dev/shm
          name: shm
  multiModel: false
  protocolVersions:
    - grpc-v2
    - v2
  supportedModelFormats:
    - autoSelect: true
      name: vLLM
  volumes:
    - emptyDir:
        medium: Memory
        sizeLimit: 2Gi
      name: shm
EOF
```

---

## Step 6 — InferenceService for Qwen3-8B

```bash
oc apply -f - <<'EOF'
apiVersion: serving.kserve.io/v1beta1
kind: InferenceService
metadata:
  name: qwen3-8b-llm
  namespace: kafka-diag-agent
  labels:
    opendatahub.io/dashboard: "true"
  annotations:
    serving.kserve.io/deploymentMode: RawDeployment
spec:
  predictor:
    model:
      modelFormat:
        name: vLLM
      runtime: vllm-runtime-llm
      storageUri: hf://RedHatAI/Qwen3-8B-FP8-dynamic
      resources:
        limits:
          nvidia.com/gpu: "1"
          memory: 14Gi
        requests:
          nvidia.com/gpu: "1"
          memory: 12Gi
    env:
      - name: HF_TOKEN
        valueFrom:
          secretKeyRef:
            name: huggingface-secret
            key: HF_TOKEN
EOF
```

Monitor — first time downloads ~8GB from HuggingFace (10-15 min):
```bash
oc get pods -n kafka-diag-agent -w
oc logs -f -n kafka-diag-agent \
  -l serving.kserve.io/inferenceservice=qwen3-8b-llm \
  -c kserve-container
oc get inferenceservice qwen3-8b-llm -n kafka-diag-agent
# Expected: READY=True
```

Smoke test:
```bash
LLM_POD=$(oc get pod -n kafka-diag-agent \
  -l serving.kserve.io/inferenceservice=qwen3-8b-llm \
  -o jsonpath='{.items[0].metadata.name}')

oc exec -n kafka-diag-agent $LLM_POD -- \
  curl -s http://localhost:8000/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"model":"/mnt/models","messages":[{"role":"user","content":"reply with one word: ready"}],"max_tokens":20,"temperature":0.1}' | \
  python3 -c "import sys,json; r=json.load(sys.stdin); print('Response:', r['choices'][0]['message']['content'])"
```

---

## Step 7 — ServingRuntime for embeddings (CPU)

> **Important**: Use `rhaiis/vllm-cpu-rhel9:3.3.0` — CPU-specific image, separate from GPU.
> `--trust-remote-code` is mandatory for `nomic-embed-text-v1.5`.
> Do not add `--task=embed` or `--device=cpu` — invalid flags in this version.

```bash
oc apply -f - <<'EOF'
apiVersion: serving.kserve.io/v1alpha1
kind: ServingRuntime
metadata:
  name: vllm-runtime-embed
  namespace: kafka-diag-agent
  labels:
    opendatahub.io/dashboard: "true"
spec:
  containers:
    - args:
        - --model=/mnt/models
        - --dtype=float32
        - --max-model-len=512
        - --trust-remote-code
      image: registry.redhat.io/rhaiis/vllm-cpu-rhel9:3.3.0
      name: kserve-container
      ports:
        - containerPort: 8000
          name: h2c
          protocol: TCP
      resources:
        limits:
          cpu: "4"
          memory: 4Gi
        requests:
          cpu: "2"
          memory: 2Gi
  multiModel: false
  protocolVersions:
    - grpc-v2
    - v2
  supportedModelFormats:
    - autoSelect: true
      name: vLLM
EOF
```

---

## Step 8 — InferenceService for nomic-embed + Service

```bash
oc apply -f - <<'EOF'
apiVersion: serving.kserve.io/v1beta1
kind: InferenceService
metadata:
  name: nomic-embed
  namespace: kafka-diag-agent
  labels:
    opendatahub.io/dashboard: "true"
  annotations:
    serving.kserve.io/deploymentMode: RawDeployment
spec:
  predictor:
    model:
      modelFormat:
        name: vLLM
      runtime: vllm-runtime-embed
      storageUri: hf://RedHatAI/nomic-embed-text-v1.5
      resources:
        limits:
          cpu: "4"
          memory: 4Gi
        requests:
          cpu: "2"
          memory: 2Gi
    env:
      - name: HF_TOKEN
        valueFrom:
          secretKeyRef:
            name: huggingface-secret
            key: HF_TOKEN
EOF
```

> **Note**: KServe creates a headless service on port 80 that does not work. Create an additional Service:

```bash
oc apply -f - <<'EOF'
apiVersion: v1
kind: Service
metadata:
  name: nomic-embed-svc
  namespace: kafka-diag-agent
spec:
  selector:
    serving.kserve.io/inferenceservice: nomic-embed
  ports:
    - port: 8000
      targetPort: 8000
  type: ClusterIP
EOF
```

Smoke test:
```bash
LLM_POD=$(oc get pod -n kafka-diag-agent \
  -l serving.kserve.io/inferenceservice=qwen3-8b-llm \
  -o jsonpath='{.items[0].metadata.name}')

oc exec -n kafka-diag-agent $LLM_POD -- \
  curl -s http://nomic-embed-svc.kafka-diag-agent.svc.cluster.local:8000/v1/embeddings \
  -H "Content-Type: application/json" \
  -d '{"model":"/mnt/models","input":"kafka consumer lag troubleshooting"}' | \
  python3 -c "import sys,json; r=json.load(sys.stdin); print('Dims:', len(r['data'][0]['embedding']))"
# Expected: Dims: 768
```

---

## Step 9 — ChromaDB with PDF PVC mounted

```bash
oc apply -f - <<'EOF'
apiVersion: apps/v1
kind: Deployment
metadata:
  name: chromadb
  namespace: kafka-diag-agent
spec:
  replicas: 1
  selector:
    matchLabels:
      app: chromadb
  template:
    metadata:
      labels:
        app: chromadb
    spec:
      serviceAccountName: kafka-diag-sa
      containers:
        - name: chromadb
          image: chromadb/chroma:latest
          ports:
            - containerPort: 8000
          env:
            - name: IS_PERSISTENT
              value: "TRUE"
            - name: PERSIST_DIRECTORY
              value: /chroma/data
            - name: ALLOW_RESET
              value: "TRUE"
          volumeMounts:
            - name: vector-store
              mountPath: /chroma/data
            - name: pdfs
              mountPath: /pdfdata
          resources:
            limits:
              memory: 1Gi
              cpu: "1"
            requests:
              memory: 512Mi
              cpu: "500m"
      volumes:
        - name: vector-store
          persistentVolumeClaim:
            claimName: pvc-vector-store
        - name: pdfs
          persistentVolumeClaim:
            claimName: pvc-pdf-knowledge
---
apiVersion: v1
kind: Service
metadata:
  name: chromadb
  namespace: kafka-diag-agent
spec:
  selector:
    app: chromadb
  ports:
    - port: 8000
      targetPort: 8000
EOF
```

Smoke test:
```bash
oc exec -n kafka-diag-agent $LLM_POD -- \
  curl -s http://chromadb.kafka-diag-agent.svc.cluster.local:8000/api/v2/heartbeat
# Expected: {"nanosecond heartbeat": ...}
```

---

## Step 10 — Upload PDFs organized by version

PDFs are mounted in ChromaDB at `/pdfdata`. ChromaDB runs as root (via `anyuid` SCC).

```
/pdfdata/
├── streams-3.1/   ← Red Hat Streams for Apache Kafka 3.1
├── streams-3.2/   ← future
└── streams-3.3/   ← future
```

### First time — fix permissions and create structure

```bash
oc exec -n kafka-diag-agent deployment/chromadb -- \
  sh -c "chmod 777 /pdfdata && mkdir -p /pdfdata/streams-3.1"
```

### Upload PDFs with oc cp

```bash
CHROMA_POD=$(oc get pod -n kafka-diag-agent -l app=chromadb \
  -o jsonpath='{.items[0].metadata.name}')

for pdf in /path/to/local/docs/*.pdf; do
  echo "Uploading: $(basename $pdf)"
  oc cp "$pdf" kafka-diag-agent/${CHROMA_POD}:/pdfdata/streams-3.1/
done

oc exec -n kafka-diag-agent deployment/chromadb -- ls -lh /pdfdata/streams-3.1/
```

### Move PDFs already in root to versioned folder

```bash
oc exec -n kafka-diag-agent deployment/chromadb -- \
  sh -c "mkdir -p /pdfdata/streams-3.1 && \
         mv /pdfdata/Red_Hat_Streams_for_Apache_Kafka-3.1-*.pdf /pdfdata/streams-3.1/"
```

### Upload a new version at any time

```bash
CHROMA_POD=$(oc get pod -n kafka-diag-agent -l app=chromadb \
  -o jsonpath='{.items[0].metadata.name}')
oc exec -n kafka-diag-agent deployment/chromadb -- mkdir -p /pdfdata/streams-3.2
for pdf in /path/to/local/streams-3.2/*.pdf; do
  oc cp "$pdf" kafka-diag-agent/${CHROMA_POD}:/pdfdata/streams-3.2/
done
```

### Download PDFs with wget from inside the cluster

```bash
oc exec -n kafka-diag-agent deployment/chromadb -- \
  sh -c "mkdir -p /pdfdata/streams-3.2 && \
         wget -q -P /pdfdata/streams-3.2/ https://public-url/doc.pdf"
```

### View PDF count by version

```bash
oc exec -n kafka-diag-agent deployment/chromadb -- \
  sh -c "for dir in /pdfdata/*/; do \
           echo \"=== \$(basename \$dir) ===\"; \
           ls \$dir*.pdf 2>/dev/null | wc -l | xargs echo 'PDFs:'; \
         done"
```

---

## Step 11 — Final validation

```bash
LLM_POD=$(oc get pod -n kafka-diag-agent \
  -l serving.kserve.io/inferenceservice=qwen3-8b-llm \
  -o jsonpath='{.items[0].metadata.name}')

echo "=== 1. LLM ==="
oc exec -n kafka-diag-agent $LLM_POD -- \
  curl -s http://localhost:8000/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"model":"/mnt/models","messages":[{"role":"user","content":"say: ok"}],"max_tokens":10}' | \
  python3 -c "import sys,json; r=json.load(sys.stdin); print('LLM OK:', r['choices'][0]['message']['content'])"

echo "=== 2. Embeddings ==="
oc exec -n kafka-diag-agent $LLM_POD -- \
  curl -s http://nomic-embed-svc.kafka-diag-agent.svc.cluster.local:8000/v1/embeddings \
  -H "Content-Type: application/json" \
  -d '{"model":"/mnt/models","input":"test"}' | \
  python3 -c "import sys,json; r=json.load(sys.stdin); print('Embeddings OK: dims=', len(r['data'][0]['embedding']))"

echo "=== 3. ChromaDB ==="
oc exec -n kafka-diag-agent $LLM_POD -- \
  curl -s http://chromadb.kafka-diag-agent.svc.cluster.local:8000/api/v2/heartbeat | \
  python3 -c "import sys,json; r=json.load(sys.stdin); print('ChromaDB OK:', 'nanosecond heartbeat' in r)"

echo "=== 4. PDFs ==="
oc exec -n kafka-diag-agent deployment/chromadb -- \
  sh -c "for dir in /pdfdata/*/; do \
           count=\$(ls \$dir*.pdf 2>/dev/null | wc -l); \
           echo \"\$(basename \$dir): \$count PDFs\"; \
         done"

echo "=== 5. RBAC ==="
oc auth can-i list kafkas \
  --as=system:serviceaccount:kafka-diag-agent:kafka-diag-sa && echo "RBAC OK"
```

Expected output:
```
=== 1. LLM ===
LLM OK: ok
=== 2. Embeddings ===
Embeddings OK: dims= 768
=== 3. ChromaDB ===
ChromaDB OK: True
=== 4. PDFs ===
streams-3.1: 18 PDFs
=== 5. RBAC ===
yes
RBAC OK
```

---

## Lessons learned

| Problem | Cause | Solution |
|---------|-------|---------|
| `ErrImagePull` with sha256 from previous project | SHA was from RHOAI 2.25 / `rhoai/odh-vllm-cuda-rhel9` image | Use `rhaiis/vllm-cuda-rhel9:3.3.0` |
| `Error 803: unsupported display driver/cuda` | RTX 5060 Ti uses CUDA 13.0, image 3.2.x only supports CUDA 12.x | Use `rhaiis/vllm-cuda-rhel9:3.3.0` with CUDA 13.0.1 |
| `READY=False` with server running | `containerPort: 8080` but vLLM 3.3 starts on `8000` | Change port to `8000` in ServingRuntime |
| ChromaDB `Permission denied` on PVC | OCP restrictive SCC blocks arbitrary UIDs | Grant `anyuid` SCC to SA and use it in the Deployment |
| nomic-embed crash with `--task=embed` | Flag does not exist in this vLLM version | Remove flag — task is auto-detected |
| nomic-embed crash with cuda image on CPU | Wrong image for CPU workloads | Use `rhaiis/vllm-cpu-rhel9:3.3.0` |
| nomic-embed crash — trust_remote_code error | Model requires remote code execution | Add `--trust-remote-code` to ServingRuntime |
| KServe headless service does not resolve | KServe creates `ClusterIP: None` on port 80 | Create additional `nomic-embed-svc` Service on port 8000 |
| ChromaDB API v1 returns error | Recent ChromaDB deprecated v1 | Use `/api/v2/` in all calls |
| `oc cp` fails — `tar not found` | ubi-minimal image does not include `tar` | Use full `ubi9` image or copy via ChromaDB pod |
| `chmod` fails in temporary pods | PVC created with root ownership | Use ChromaDB pod (runs as root via anyuid) to manage PDFs |
| Temporary pod needed to upload PDFs | PVC not directly accessible | Mount PDF PVC permanently in ChromaDB at `/pdfdata` |
