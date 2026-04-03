# Phase 2 — Quarkus App + Basic Diagnostic Tools
## Kafka Diagnostic Agent

> **Goal**: Deploy the Quarkus app on OCP with a functional LangChain4j agent,
> basic Kubernetes/OCP and Strimzi report tools, and the Web UI v1.
> No RAG yet — the agent responds using only live cluster data.

---

## Prerequisites

- Phase 1 completed and validated
- GitHub repository: https://github.com/emunozd/kafka-diag-agent
- `oc` CLI authenticated to the `kafka-diag-agent` namespace

---

## Step 1 — Build the app on OCP via S2I

The BuildConfig is already included in the Helm chart. If you used Helm, skip to step 2.
Otherwise apply it manually:

```bash
oc apply -f - <<'EOF'
apiVersion: image.openshift.io/v1
kind: ImageStream
metadata:
  name: kafka-diag-agent
  namespace: kafka-diag-agent
---
apiVersion: build.openshift.io/v1
kind: BuildConfig
metadata:
  name: kafka-diag-agent
  namespace: kafka-diag-agent
spec:
  source:
    type: Git
    git:
      uri: https://github.com/emunozd/kafka-diag-agent
      ref: main
    contextDir: quarkus
  strategy:
    type: Source
    sourceStrategy:
      from:
        kind: ImageStreamTag
        namespace: openshift
        name: java:latest
      env:
        - name: MAVEN_ARGS_APPEND
          value: "-Dquarkus.package.type=uber-jar"
  output:
    to:
      kind: ImageStreamTag
      name: kafka-diag-agent:latest
  triggers:
    - type: ConfigChange
EOF
```

Start the build and follow logs:
```bash
oc start-build kafka-diag-agent -n kafka-diag-agent --follow
```

> The first build takes 3-5 minutes downloading Maven dependencies.
> Subsequent builds use cache and are significantly faster.

---

## Step 2 — Deploy the app

```bash
oc apply -f - <<'EOF'
apiVersion: apps/v1
kind: Deployment
metadata:
  name: kafka-diag-app
  namespace: kafka-diag-agent
spec:
  replicas: 1
  selector:
    matchLabels:
      app: kafka-diag-app
  template:
    metadata:
      labels:
        app: kafka-diag-app
    spec:
      serviceAccountName: kafka-diag-sa
      containers:
        - name: kafka-diag-app
          image: image-registry.openshift-image-registry.svc:5000/kafka-diag-agent/kafka-diag-agent:latest
          ports:
            - containerPort: 8080
          env:
            - name: APP_NAMESPACE
              valueFrom:
                fieldRef:
                  fieldPath: metadata.namespace
            - name: LLM_BASE_URL
              value: "http://qwen3-8b-llm-predictor.kafka-diag-agent.svc.cluster.local:8000/v1"
            - name: LLM_API_KEY
              value: "none"
            - name: LLM_MODEL_NAME
              value: "/mnt/models"
            - name: EMBED_BASE_URL
              value: "http://nomic-embed-svc.kafka-diag-agent.svc.cluster.local:8000/v1"
            - name: EMBED_MODEL_NAME
              value: "/mnt/models"
            - name: CHROMA_BASE_URL
              value: "http://chromadb.kafka-diag-agent.svc.cluster.local:8000"
            - name: PDF_BASE_PATH
              value: "/pdfdata"
            - name: RH_OFFLINE_TOKEN
              valueFrom:
                secretKeyRef:
                  name: rh-api-credentials
                  key: RH_OFFLINE_TOKEN
                  optional: true
          volumeMounts:
            - name: pdfs
              mountPath: /pdfdata
              readOnly: true
          resources:
            limits:
              memory: 1Gi
              cpu: "1"
            requests:
              memory: 512Mi
              cpu: "500m"
      volumes:
        - name: pdfs
          persistentVolumeClaim:
            claimName: pvc-pdf-knowledge
---
apiVersion: v1
kind: Service
metadata:
  name: kafka-diag-app
  namespace: kafka-diag-agent
spec:
  selector:
    app: kafka-diag-app
  ports:
    - port: 8080
      targetPort: 8080
---
apiVersion: route.openshift.io/v1
kind: Route
metadata:
  name: kafka-diag-app
  namespace: kafka-diag-agent
spec:
  to:
    kind: Service
    name: kafka-diag-app
  port:
    targetPort: 8080
  tls:
    termination: edge
    insecureEdgeTerminationPolicy: Redirect
EOF
```

Monitor:
```bash
oc get pods -n kafka-diag-agent -l app=kafka-diag-app -w
```

Get the app URL:
```bash
oc get route kafka-diag-app -n kafka-diag-agent -o jsonpath='{.spec.host}'
```

---

## Step 3 — Health check

```bash
APP_URL=$(oc get route kafka-diag-app -n kafka-diag-agent \
  -o jsonpath='{.spec.host}')

curl -s https://${APP_URL}/api/health
# Expected: {"status":"ok","agent":"kafka-diag-agent"}
```

---

## Step 4 — Diagnosis smoke tests

```bash
APP_URL=$(oc get route kafka-diag-app -n kafka-diag-agent \
  -o jsonpath='{.spec.host}')

# Test 1: architecture summary
curl -s https://${APP_URL}/api/diagnose \
  -H "Content-Type: application/json" \
  -d '{"question":"give me a brief status of the current cluster","namespace":"kafka-diag-agent"}' \
  | python3 -c "import sys,json; r=json.load(sys.stdin); print(r['answer'])"

# Test 2: namespace override
curl -s https://${APP_URL}/api/diagnose \
  -H "Content-Type: application/json" \
  -d '{"question":"list all kafka topics","namespace":"my-kafka-ns"}' \
  | python3 -c "import sys,json; r=json.load(sys.stdin); print(r['answer'])"
```

---

## Step 5 — Web UI

Open in browser:
```
https://<APP_URL>/advisor.html
```

---

## Step 6 — Redeploy after code changes

```bash
git push origin main
oc start-build kafka-diag-agent -n kafka-diag-agent --follow
# The deployment updates automatically via the ImageStream trigger
```

---

## Key design decisions in Phase 2

### /no_think enforced in system prompt
The system prompt starts with `/no_think` — this disables Qwen3 reasoning mode
which wastes tokens with `<think>` blocks that add no value to diagnostic responses.
Additionally, `DiagnosticResource.java` strips any residual `<think>...</think>`
blocks before returning the response to the client.

### Tool calling and Qwen3
The `--enable-auto-tool-choice` and `--tool-call-parser=hermes` flags in the
ServingRuntime are mandatory for Qwen3 to use LangChain4j `@Tool` methods.

### Namespace handling
- If no namespace is specified, `APP_NAMESPACE` (the pod's own namespace) is used.
- If the user writes "analyze my kafka cluster in namespace my-ns", the agent
  extracts the namespace from the text and passes it to all tool calls automatically.
- The namespace is prepended to the question: `[namespace: my-ns] question...`

### Flexible LLM provider
All LLM configuration comes from environment variables injected via OCP Secrets.
To switch from Qwen3 to Claude or OpenAI, create the `llm-config` Secret and
restart the pod — no code rebuild required.

### RAG, upload, and KCS
These are stubs in this phase — they return informative placeholder messages
but do not block the agent. Full implementations arrive in Phases 3, 4, and 5.
