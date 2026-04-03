# Fase 1 — Infraestructura base en OCP
## Kafka Diagnostic Agent — RHOAI 3.3 / CRC / RTX 5060 Ti

> **Stack validado**: RHOAI 3.3 · OCP CRC · NVIDIA RTX 5060 Ti · CUDA 13.0  
> **Imágenes**: `rhaiis/vllm-cuda-rhel9:3.3.0` (GPU) · `rhaiis/vllm-cpu-rhel9:3.3.0` (CPU)  
> **Modelos**: `RedHatAI/Qwen3-8B-FP8-dynamic` (LLM) · `RedHatAI/nomic-embed-text-v1.5` (embeddings)

---

## URLs internas (para Fases siguientes)

| Componente | URL interna | Puerto |
|-----------|-------------|--------|
| LLM Qwen3-8B | `http://qwen3-8b-llm-predictor.kafka-diag-agent.svc.cluster.local` | 8000 |
| Embeddings nomic | `http://nomic-embed-svc.kafka-diag-agent.svc.cluster.local` | 8000 |
| ChromaDB | `http://chromadb.kafka-diag-agent.svc.cluster.local` | 8000 |
| PDFs en pod ChromaDB | `/pdfdata` | — |
| PDFs Streams 3.1 | `/pdfdata/streams-3.1/` | — |
| PDFs Streams 3.2+ | `/pdfdata/streams-3.2/` (futuro) | — |

> **Nota**: El modelo se referencia como `/mnt/models` en el campo `model` de los requests a vLLM.  
> **Nota**: ChromaDB usa `/api/v2/` — la v1 está deprecada en versiones recientes.  
> **Nota**: Qwen3 tiene modo thinking activo por defecto — puede aparecer `<think>` en respuestas. Se controla desde el system prompt en Fase 2 con `/no_think`.  
> **Nota**: Los PDFs se organizan por subcarpeta de versión (`streams-3.1/`, `streams-3.2/`, etc.). El indexer en Fase 3 escanea recursivamente y guarda la versión como metadato en cada chunk del RAG.

---

## Paso 0 — Verificar estado del CRC

```bash
crc status
oc whoami
oc get nodes

# Verificar GPU allocatable — debe mostrar nvidia.com/gpu: 1
oc describe node | grep -A 10 "Allocatable"

# Verificar RHOAI 3.3 instalado
oc get csv -n redhat-ods-operator | grep rhods
# Esperado: rhods-operator.3.3.0   Succeeded
```

> Si GPU aparece `0` en allocatable después de un reboot, reiniciar el device plugin:
> ```bash
> oc delete pod -n nvidia-gpu-operator \
>   $(oc get pods -n nvidia-gpu-operator | grep device-plugin | awk '{print $1}')
> ```

---

## Paso 1 — Crear el namespace

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

## Paso 2 — Crear los PVCs

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

Verificar:
```bash
oc get pvc -n kafka-diag-agent
```

> **Nota**: Con `crc-csi-hostpath-provisioner` los PVCs quedan en `Pending` hasta que un pod los monte por primera vez. Es normal — se bindean automáticamente al primer uso.

---

## Paso 3 — ServiceAccount y RBAC

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

Verificar:
```bash
oc get sa kafka-diag-sa -n kafka-diag-agent
oc get clusterrolebinding kafka-diag-reader-binding

# Prueba de fuego — debe responder: yes
oc auth can-i list kafkas \
  --as=system:serviceaccount:kafka-diag-agent:kafka-diag-sa
```

Dar SCC `anyuid` al SA (necesario para ChromaDB):
```bash
oc adm policy add-scc-to-user anyuid \
  -z kafka-diag-sa \
  -n kafka-diag-agent
```

---

## Paso 4 — Secrets

### Secret de HuggingFace (obligatorio para descargar modelos)

Generar el token en: https://huggingface.co/settings/tokens

```bash
oc create secret generic huggingface-secret \
  --from-literal=HF_TOKEN=hf_xxxxxxxxxxxxxxxxxxxx \
  -n kafka-diag-agent
```

### Secret de Red Hat API / KCS (completamente opcional)

Si no se crea, la app arranca igual y usa modo fallback de KCS.

```bash
# Solo si tienes offline token de: https://access.redhat.com/management/api
oc create secret generic rh-api-credentials \
  --from-literal=RH_OFFLINE_TOKEN=tu_token_aqui \
  -n kafka-diag-agent
```

### Secret de LLM externo (opcional — para usar Claude/OpenAI en vez de GPU local)

```bash
# Ejemplo con Anthropic Claude
oc create secret generic llm-config \
  --from-literal=LLM_BASE_URL=https://api.anthropic.com/v1 \
  --from-literal=LLM_API_KEY=sk-ant-... \
  --from-literal=LLM_MODEL_NAME=claude-sonnet-4-6 \
  -n kafka-diag-agent
```

---

## Paso 5 — ServingRuntime para el LLM (GPU · CUDA 13.0)

> **Importante**: Usar `rhaiis/vllm-cuda-rhel9:3.3.0` — versiones anteriores (3.2.x) no soportan CUDA 13.0 de la RTX 5060 Ti.  
> Puerto del contenedor es `8000`, no `8080`.  
> Flags `--enable-auto-tool-choice` y `--tool-call-parser=hermes` son obligatorios para tool calling con Qwen3.

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

## Paso 6 — InferenceService para Qwen3-8B (LLM principal)

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

Monitorear — la primera vez descarga ~8GB desde HuggingFace (10-15 min):
```bash
# Terminal 1
oc get pods -n kafka-diag-agent -w

# Terminal 2 — logs cuando el pod arranque
oc logs -f -n kafka-diag-agent \
  -l serving.kserve.io/inferenceservice=qwen3-8b-llm \
  -c kserve-container

# Estado final
oc get inferenceservice qwen3-8b-llm -n kafka-diag-agent
# Esperado: READY=True
```

Prueba de fuego:
```bash
LLM_POD=$(oc get pod -n kafka-diag-agent \
  -l serving.kserve.io/inferenceservice=qwen3-8b-llm \
  -o jsonpath='{.items[0].metadata.name}')

oc exec -n kafka-diag-agent $LLM_POD -- \
  curl -s http://localhost:8000/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "/mnt/models",
    "messages": [{"role":"user","content":"responde solo la palabra: listo"}],
    "max_tokens": 20,
    "temperature": 0.1
  }' | python3 -c "
import sys,json
r=json.load(sys.stdin)
print('Respuesta:', r['choices'][0]['message']['content'])
"
```

---

## Paso 7 — ServingRuntime para embeddings (CPU)

> **Importante**: Usar `rhaiis/vllm-cpu-rhel9:3.3.0` — imagen específica para CPU, distinta a la de GPU.  
> Flag `--trust-remote-code` es obligatorio para `nomic-embed-text-v1.5`.  
> No usar `--task=embed` ni `--device=cpu` — no son flags válidos en esta versión.

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

## Paso 8 — InferenceService para nomic-embed + Service

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

> **Nota**: KServe crea un servicio headless en puerto 80 que no funciona. Crear Service adicional:

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

Prueba de fuego:
```bash
LLM_POD=$(oc get pod -n kafka-diag-agent \
  -l serving.kserve.io/inferenceservice=qwen3-8b-llm \
  -o jsonpath='{.items[0].metadata.name}')

oc exec -n kafka-diag-agent $LLM_POD -- \
  curl -s http://nomic-embed-svc.kafka-diag-agent.svc.cluster.local:8000/v1/embeddings \
  -H "Content-Type: application/json" \
  -d '{"model":"/mnt/models","input":"kafka consumer lag troubleshooting"}' | \
  python3 -c "
import sys,json
r=json.load(sys.stdin)
print('Dims:', len(r['data'][0]['embedding']))
"
# Esperado: Dims: 768
```

---

## Paso 9 — ChromaDB con PVC de PDFs montado

> **Nota**: ChromaDB usa el SA `kafka-diag-sa` que tiene SCC `anyuid` — necesario para escribir en los PVCs.  
> El PVC de PDFs se monta permanentemente en `/pdfdata` para subir PDFs en cualquier momento sin pods temporales.

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

Prueba de fuego:
```bash
LLM_POD=$(oc get pod -n kafka-diag-agent \
  -l serving.kserve.io/inferenceservice=qwen3-8b-llm \
  -o jsonpath='{.items[0].metadata.name}')

oc exec -n kafka-diag-agent $LLM_POD -- \
  curl -s http://chromadb.kafka-diag-agent.svc.cluster.local:8000/api/v2/heartbeat
# Esperado: {"nanosecond heartbeat": ...}
```

---

## Paso 10 — Subir PDFs al PVC organizados por versión

El PVC de PDFs está montado en ChromaDB en `/pdfdata`. ChromaDB corre como root (via `anyuid` SCC) y puede escribir sin restricciones.

Los PDFs se organizan en subcarpetas por versión del producto — así el indexer en Fase 3 puede guardar la versión como metadato en cada chunk del RAG, y el agente puede indicar al usuario de qué versión proviene cada recomendación.

```
/pdfdata/
├── streams-3.1/   ← Red Hat Streams for Apache Kafka 3.1
├── streams-3.2/   ← futuro
└── streams-3.3/   ← futuro
```

### Primera vez — arreglar permisos y crear estructura

```bash
oc exec -n kafka-diag-agent deployment/chromadb -- \
  sh -c "chmod 777 /pdfdata && mkdir -p /pdfdata/streams-3.1"
```

### Subir PDFs con oc cp (desde laptop)

```bash
CHROMA_POD=$(oc get pod -n kafka-diag-agent -l app=chromadb \
  -o jsonpath='{.items[0].metadata.name}')

# Subir todos los PDFs de una versión local
for pdf in /opt/disk/streams_docs/*.pdf; do
  echo "Copiando: $(basename $pdf)"
  oc cp "$pdf" kafka-diag-agent/${CHROMA_POD}:/pdfdata/streams-3.1/
done

# Verificar
oc exec -n kafka-diag-agent deployment/chromadb -- ls -lh /pdfdata/streams-3.1/
```

### Organizar PDFs ya subidos en raíz (si ya están en /pdfdata sin carpeta)

```bash
oc exec -n kafka-diag-agent deployment/chromadb -- \
  sh -c "mkdir -p /pdfdata/streams-3.1 && \
         mv /pdfdata/Red_Hat_Streams_for_Apache_Kafka-3.1-*.pdf /pdfdata/streams-3.1/"

# Verificar estructura
oc exec -n kafka-diag-agent deployment/chromadb -- \
  sh -c "find /pdfdata -name '*.pdf' | sort"
```

### Subir PDFs de una versión nueva en cualquier momento

```bash
CHROMA_POD=$(oc get pod -n kafka-diag-agent -l app=chromadb \
  -o jsonpath='{.items[0].metadata.name}')

# Crear carpeta de nueva versión
oc exec -n kafka-diag-agent deployment/chromadb -- mkdir -p /pdfdata/streams-3.2

# Subir los PDFs
for pdf in /ruta/local/streams-3.2/*.pdf; do
  oc cp "$pdf" kafka-diag-agent/${CHROMA_POD}:/pdfdata/streams-3.2/
done
```

### Descargar PDFs con wget desde dentro del clúster

```bash
# Útil cuando no tienes los PDFs localmente o trabajas por SSH sin display
oc exec -n kafka-diag-agent deployment/chromadb -- \
  sh -c "mkdir -p /pdfdata/streams-3.2 && \
         wget -q -P /pdfdata/streams-3.2/ https://url-publica/streams-3.2-doc.pdf"
```

### Ver resumen de PDFs por versión

```bash
oc exec -n kafka-diag-agent deployment/chromadb -- \
  sh -c "for dir in /pdfdata/*/; do \
           echo \"=== \$(basename \$dir) ===\"; \
           ls \$dir*.pdf 2>/dev/null | wc -l | xargs echo 'PDFs:'; \
         done"
```

> **Nota para Fase 3**: El PDF Indexer escaneará `/pdfdata` recursivamente. Para cada PDF guardará en los metadatos del chunk: `version` (extraído del nombre de la carpeta, ej. `streams-3.1`), `filename` y `sha256`. Así el agente puede decir "según la documentación de Streams 3.1..." y en el futuro priorizar la versión más reciente disponible.
>
> **Nota para Fase 5**: El file watcher dinámico escaneará toda la estructura `/pdfdata/**/` y procesará automáticamente cualquier PDF nuevo en cualquier subcarpeta sin reiniciar la app.

---

## Paso 11 — Validación final completa

```bash
LLM_POD=$(oc get pod -n kafka-diag-agent \
  -l serving.kserve.io/inferenceservice=qwen3-8b-llm \
  -o jsonpath='{.items[0].metadata.name}')

echo "=== 1. LLM ==="
oc exec -n kafka-diag-agent $LLM_POD -- \
  curl -s http://localhost:8000/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"model":"/mnt/models","messages":[{"role":"user","content":"di: ok"}],"max_tokens":10}' | \
  python3 -c "import sys,json; r=json.load(sys.stdin); print('LLM OK:', r['choices'][0]['message']['content'])"

echo "=== 2. Embeddings ==="
oc exec -n kafka-diag-agent $LLM_POD -- \
  curl -s http://nomic-embed-svc.kafka-diag-agent.svc.cluster.local:8000/v1/embeddings \
  -H "Content-Type: application/json" \
  -d '{"model":"/mnt/models","input":"test"}' | \
  python3 -c "import sys,json; r=json.load(sys.stdin); print('Embed OK: dims=', len(r['data'][0]['embedding']))"

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

Resultado esperado:
```
=== 1. LLM ===
LLM OK: ok
=== 2. Embeddings ===
Embed OK: dims= 768
=== 3. ChromaDB ===
ChromaDB OK: True
=== 4. PDFs ===
streams-3.1: 18 PDFs
=== 5. RBAC ===
yes
RBAC OK
```

---

## Lecciones aprendidas

| Problema | Causa | Solución |
|---------|-------|---------|
| `ErrImagePull` con sha256 del proyecto anterior | SHA era de RHOAI 2.25 / imagen `rhoai/odh-vllm-cuda-rhel9` | Usar `rhaiis/vllm-cuda-rhel9:3.3.0` |
| `Error 803: unsupported display driver/cuda` | RTX 5060 Ti usa CUDA 13.0, imagen 3.2.x solo soporta CUDA 12.x | Usar `rhaiis/vllm-cuda-rhel9:3.3.0` con CUDA 13.0.1 |
| `READY=False` con servidor corriendo | `containerPort: 8080` pero vLLM 3.3 arranca en `8000` | Cambiar port a `8000` en ServingRuntime |
| ChromaDB `Permission denied` en PVC | SCC restrictivo de OCP no permite UIDs arbitrarios | Dar `anyuid` SCC al SA y usarlo en el Deployment |
| nomic-embed crash con `--task=embed` | Flag no existe en esta versión de vLLM | Quitar el flag — la tarea se detecta automáticamente |
| nomic-embed crash con imagen cuda en CPU | Imagen equivocada para CPU | Usar `rhaiis/vllm-cpu-rhel9:3.3.0` |
| nomic-embed crash con `trust_remote_code` | El modelo requiere código remoto | Agregar `--trust-remote-code` al ServingRuntime |
| Servicio headless KServe no resuelve | KServe crea `ClusterIP: None` en puerto 80 | Crear Service adicional `nomic-embed-svc` en puerto 8000 |
| ChromaDB API v1 retorna error | Versión reciente de ChromaDB deprecó v1 | Usar `/api/v2/` en todas las llamadas |
| `oc cp` falla con `tar not found` | Imagen ubi-minimal no tiene `tar` | Usar imagen `ubi9` completa o copiar via ChromaDB |
| `chmod` falla en pods temporales | PVC con ownership root, pods no-root no pueden cambiar permisos | Usar ChromaDB (corre como root via anyuid) para gestionar PDFs |
| Necesidad de pod temporal para subir PDFs | PVC no accesible directamente | Montar PVC de PDFs permanentemente en ChromaDB en `/pdfdata` |
