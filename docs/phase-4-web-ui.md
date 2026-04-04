# Phase 4 — Web UI v2 + Strimzi Report Upload
## Kafka Diagnostic Agent

> **Goal**: Extend the Web UI to support two diagnostic modes — live cluster access
> via the Kubernetes API, and offline analysis of a Strimzi `report.sh` ZIP output
> uploaded by the user. Enrich both modes with RAG documentation context.
>
> **Result**: The agent can now diagnose Kafka clusters without direct cluster access,
> using the official Strimzi report tool output. Documentation is cited with exact
> document name, version, and page number from the indexed PDFs.

---

## What was built

| File | Change |
|------|--------|
| `resource/DiagnosticResource.java` | Added `POST /api/upload-report` and `POST /api/diagnose-report` endpoints |
| `tools/ReportUploadTool.java` | Full implementation — ZIP parsing, section extraction, ThreadLocal lifecycle |
| `resources/META-INF/resources/advisor.html` | Two-tab UI: Live Cluster / Uploaded Report |
| `agent/KafkaDiagnosticAgent.java` | Improved `@SystemMessage` with mandatory sequences for both modes |
| `application.properties` | `max-tokens=2048`, tuned for 14k context window |

---

## Architecture

```
User uploads report.sh ZIP
    ↓
POST /api/diagnose-report (multipart/form-data: zip + question)
    ↓
DiagnosticResource.extractZipContents()
    ↓
Iterates ZIP entries → strips date folder + reports/ prefix
Extracts: .yaml .yml .json .txt .log .properties .conf
Max 50KB per file, max 200 files
    ↓
ReportUploadTool.setReportFiles(Map<path, content>)  ← ThreadLocal
    ↓
agent.diagnose(question) — same thread
    ↓
Agent calls analyzeUploadedReport("summary")
Agent calls analyzeUploadedReport("kafka")
Agent calls analyzeUploadedReport("pods")
Agent calls analyzeUploadedReport("events")
Agent calls queryDocumentation("specific issue found")
    ↓
ReportUploadTool.clearReportFiles()  ← finally block
    ↓
Structured response: Summary → Findings → Documentation Context → Recommendations
```

---

## Strimzi report.sh ZIP structure

The `report.sh` tool generates a ZIP with this structure:

```
report-DD-MM-YYYY_HH-MM-SS/
  reports/
    kafkas/<cluster>.yaml
    kafkatopics/<topic>.yaml
    kafkanodepools/<pool>.yaml
    pods/                        ← empty if cluster never started
    events/events.txt
    secrets/<secret>.yaml
    clusterroles/*.yaml
    configmaps/
    services/
    ...
```

`DiagnosticResource.normalizePath()` strips the date folder and `reports/` prefix,
so paths are stored as `kafkas/bud-kafka-cluster.yaml`, `events/events.txt`, etc.

---

## Key design decisions

### Single request — ZIP + question together
The initial design used two separate requests (upload then diagnose), which broke
because Quarkus uses different threads per request and `ThreadLocal` doesn't survive
across threads. The fix was to combine both into a single `multipart/form-data`
request to `/api/diagnose-report`, so the ZIP extraction and agent invocation happen
in the same thread.

### ThreadLocal for report files
`ReportUploadTool` stores extracted files in a `ThreadLocal<Map<String, String>>`.
`DiagnosticResource` always calls `clearReportFiles()` in a `finally` block to
prevent memory leaks between requests.

### Section-based tool calls
The agent calls `analyzeUploadedReport` multiple times with different aspects
(`summary`, `kafka`, `pods`, `events`, etc.). Each call returns only the files
matching that directory prefix, keeping individual tool responses small enough
to fit within the LLM context window.

### RAG in both modes
`queryDocumentation` is called in both report-mode and live-mode. The system prompt
enforces this as a mandatory step. Documentation results include exact document name,
version (e.g. `streams-3.1`), and page number so the agent can cite them precisely.

---

## GPU / vLLM tuning required

The default `max-model-len=4096` was insufficient for multi-tool-call sessions.
The following ServingRuntime configuration was required:

| Parameter | Before | After |
|-----------|--------|-------|
| `--max-model-len` | 4096 | 14000 |
| `--gpu-memory-utilization` | 0.70 | 0.90 |
| KV cache available | 0.75 GiB | 3.84 GiB |
| KV cache tokens | 5,440 | 27,968 |

Apply with:
```bash
oc patch servingruntimes vllm-runtime-llm -n kafka-diag-agent --type=json -p='[
  {"op": "replace", "path": "/spec/containers/0/args/2", "value": "--max-model-len=14000"},
  {"op": "replace", "path": "/spec/containers/0/args/3", "value": "--gpu-memory-utilization=0.90"}
]'
oc delete pod -n kafka-diag-agent -l serving.kserve.io/inferenceservice=qwen3-8b-llm
```

**Note**: If the pod keeps starting with old values, check for stale ReplicaSets and
scale the correct one manually.

---

## HAProxy timeout

The OCP route needs an extended timeout for long-running agent sessions:

```bash
oc annotate route kafka-diag-app -n kafka-diag-agent \
  --overwrite haproxy.router.openshift.io/timeout=300s
```

---

## Lessons learned

| Problem | Cause | Fix |
|---------|-------|-----|
| `ThreadLocal` lost between requests | Quarkus uses different threads per request | Combine upload + diagnose in single request |
| `Error: Unexpected token '<'` in browser | HAProxy timeout cut the connection | `haproxy.router.openshift.io/timeout=300s` |
| `max_tokens too large` error | `input_tokens + max_tokens > max_model_len` | Increased `max-model-len` to 14000 |
| Pod keeps starting with old `max-model-len` | Stale ReplicaSets cached old args | Scale correct RS manually |
| Agent not calling `queryDocumentation` | System prompt not explicit enough | Mandatory sequence with numbered steps |
| Agent inventing documentation URLs | No constraint in system prompt | Explicit rule: never invent links or section names |
| Agent citing docs without source | Format not specified | Explicit citation format: Document, Version, Page |

---

## Validation

### Report mode
```bash
# From your laptop — upload ZIP and diagnose
curl -sk -X POST \
  https://kafka-diag-app-kafka-diag-agent.apps-crc.testing/api/diagnose-report \
  -F "zip=@/path/to/report-DD-MM-YYYY.zip" \
  -F "question=analyze the uploaded report and summarize all findings" \
  | python3 -c "import sys,json; r=json.load(sys.stdin); print(r['answer'])"
```

### Live cluster mode
```bash
curl -sk https://kafka-diag-app-kafka-diag-agent.apps-crc.testing/api/diagnose \
  -H "Content-Type: application/json" \
  -d '{"question":"analyze any issues in my environment","namespace":"kafka-ns"}' \
  | python3 -c "import sys,json; r=json.load(sys.stdin); print(r['answer'])"
```

### Expected output structure
```
## Summary
[one paragraph describing the overall state]

## Findings
[bullet points with cluster name, namespace, VERSION, replicas, status, quoted errors]

## Documentation Context
From [document.pdf] (v[version], p.[page]): [exact excerpt]

## Recommendations
[numbered actionable steps]
```
