# Phase 5 — KCS Integration + Dynamic PDF Watcher
## Kafka Diagnostic Agent

> **Goal**: Connect the agent to the Red Hat Customer Portal KCS (Knowledge
> Centered Support) API for real-time article search, and implement a background
> PDF watcher that automatically indexes new documentation files without
> requiring an application restart.
>
> **Result**: The agent now enriches every diagnosis with relevant KCS solutions
> from the Red Hat Knowledge Base, and new PDFs dropped into the knowledge base
> volume are automatically indexed within 60 seconds.

---

## What was built

| File | Change |
|------|--------|
| `tools/KCSSearchTool.java` | Full implementation — OAuth2 token exchange + KCS API search |
| `rag/PDFWatcher.java` | Full implementation — scheduled background scanner with SHA-256 dedup |
| `resource/DiagnosticResource.java` | Pre-fetch RAG + KCS before agent invocation in both modes |
| `agent/KafkaDiagnosticAgent.java` | Updated `@SystemMessage` with KCS instructions and no-duplicate rule |

---

## KCS Search Tool

### Authentication flow

Red Hat KCS requires OAuth2 authentication via the Red Hat SSO endpoint:

```
Offline Token (from OCP Secret rh-api-credentials)
    ↓
POST https://sso.redhat.com/auth/realms/redhat-external/protocol/openid-connect/token
    grant_type=refresh_token&client_id=rhsm-api&refresh_token=<offline_token>
    ↓
Access Token (short-lived)
    ↓
GET https://api.access.redhat.com/support/search/kcs
    ?q=<query>&rows=5&language=en&fq=documentKind:Solution
    Authorization: Bearer <access_token>
    ↓
KCS Solution titles and view_uri URLs
```

### Secret setup

```bash
oc create secret generic rh-api-credentials \
  -n kafka-diag-agent \
  --from-literal=RH_OFFLINE_TOKEN=<your-offline-token>
```

The offline token is available at https://access.redhat.com/management/api.
The Deployment already references this secret with `optional: true` — the app
starts normally without it and falls back to URL-based search.

### Operating modes

**With offline token**: exchanges token for access token on each call, queries
the KCS API, parses `publishedTitle` and `view_uri` from the response, returns
a formatted list of relevant solutions.

**Without token (fallback)**: returns a pre-built search URL pointing to
`access.redhat.com/search` with the query and AMQ Streams product filter.

### Deprecated API

The old endpoint `https://api.access.redhat.com/rs/cases/solutions` returned
HTTP 410 (Gone). The correct endpoint is:

```
https://api.access.redhat.com/support/search/kcs?q=<query>&rows=5&language=en&fq=documentKind:Solution
```

---

## PDF Watcher

### Design

The watcher runs as a background scheduled thread that starts after the initial
startup indexing completes (delay = 2× interval to avoid overlapping with startup).

```
App startup
    ↓
PDFIndexer.indexAll() — initial scan (pdf-indexer thread)
    ↓
PDFWatcher.start() — scheduled scanner starts after 2× interval
    ↓
Every 60s: PDFWatcher.scan()
    ↓
PDFIndexer.indexAll() — re-uses the same SHA-256 dedup logic
    ↓
Only new or changed PDFs are indexed — existing ones are skipped
```

### Configuration

```properties
kafka.diag.pdf.base-path=/pdfdata          # default
kafka.diag.pdf.watcher.interval=60         # seconds
kafka.diag.pdf.watcher.enabled=true
```

### Adding new PDFs dynamically

```bash
# Copy a new PDF to the knowledge base volume
oc cp /local/path/new-doc.pdf \
  kafka-diag-agent/<app-pod>:/pdfdata/streams-3.1/new-doc.pdf

# Within 60 seconds, the watcher will detect and index it
# Watch the logs:
oc logs -f -n kafka-diag-agent deployment/kafka-diag-app | grep "pdf-watcher"
```

Expected log output:
```
INFO  [com.red.kaf.dia.rag.PDFIndexer] (pdf-watcher) Starting PDF indexing from: /pdfdata
INFO  [com.red.kaf.dia.rag.PDFIndexer] (pdf-watcher) Indexing: /pdfdata/streams-3.1/new-doc.pdf
INFO  [com.red.kaf.dia.rag.PDFIndexer] (pdf-watcher)   new-doc.pdf → 45 pages, 28 chunks
INFO  [com.red.kaf.dia.rag.PDFIndexer] (pdf-watcher)   Indexed new-doc.pdf — 28 chunks stored
INFO  [com.red.kaf.dia.rag.PDFIndexer] (pdf-watcher) PDF indexing complete — indexed=1 skipped=18 failed=0
```

---

## Pre-fetch strategy

Instead of relying solely on the agent to call `queryDocumentation` and
`searchKCS` (which it would sometimes skip), both modes now pre-fetch context
before invoking the agent:

```
Report mode:
    extractZipContents(zip)
        ↓
    extractIssue(kafkaYAML) → specific error message from conditions.message
        ↓
    prefetchRAG(issueQuery)   → RAG context injected into prompt
    prefetchKCS(issueQuery)   → KCS articles injected into prompt
        ↓
    agent.diagnose(enrichedPrompt)

Live mode:
    prefetchRAG(userQuestion)
    prefetchKCS(userQuestion)
        ↓
    agent.diagnose(enrichedPrompt)
```

### Issue extraction from Strimzi YAML

The `extractIssue` method parses the `conditions[].message` field from the
Kafka cluster YAML. This field contains the exact error text from the Strimzi
operator, which is used as the RAG/KCS query instead of the generic user question.

Example:
```yaml
status:
  conditions:
    message: 'The Kafka cluster bud-kafka-cluster is invalid: [At least one KafkaNodePool
      with the controller role and at least one replica is required when KRaft mode is enabled]'
    reason: InvalidResourceException
    type: NotReady
```

Extracted query: `The Kafka cluster bud-kafka-cluster is invalid: [At least one KafkaNodePool with the controller role...]`

The method handles:
- Single-line messages
- Multi-line YAML flow scalars (continuation lines with higher indentation)
- Single-quoted and double-quoted values
- Truncation to 200 characters for embedding efficiency

### ZIP path normalization

Strimzi `report.sh` ZIP files may have two different structures:

```
# With date folder:
report-08-01-2026_18-47-12/reports/kafkas/cluster.yaml

# Without date folder:
reports/kafkas/cluster.yaml
```

`normalizePath()` handles both cases, always producing `kafkas/cluster.yaml`.

---

## Lessons learned

| Problem | Cause | Fix |
|---------|-------|-----|
| KCS API returned HTTP 410 | Old `/rs/cases/solutions` endpoint deprecated | New endpoint: `/support/search/kcs?fq=documentKind:Solution` |
| KCS API returned HTTP 400 | Wrong query parameter name (`keyword` vs `q`) | Use `q=` parameter |
| KCS returned Chinese results | No language filter | Add `language=en` parameter |
| KCS parser returned empty | `publishedTitle` field not found by indexOf | Correct field name confirmed via curl test |
| RAG/KCS used generic query | `extractByPrefix` used wrong path prefix | `normalizePath` fixed to strip `reports/` prefix |
| Agent skipped RAG and KCS steps | Model decides not to call tools after 4 tool calls | Pre-fetch in Java code — context always available |
| Documentation duplicated in response | Agent cited pre-fetched context + tool results | Added explicit no-duplicate rule in system prompt |

---

## Validation

```bash
# Verify KCS token exchange works
TOKEN=$(curl -s -X POST \
  "https://sso.redhat.com/auth/realms/redhat-external/protocol/openid-connect/token" \
  -d "grant_type=refresh_token&client_id=rhsm-api&refresh_token=$(oc get secret rh-api-credentials -n kafka-diag-agent -o jsonpath='{.data.RH_OFFLINE_TOKEN}' | base64 -d)" \
  | python3 -c "import sys,json; r=json.load(sys.stdin); print(r['access_token'])")

# Verify KCS API returns solutions
curl -s -H "Authorization: Bearer $TOKEN" \
  "https://api.access.redhat.com/support/search/kcs?q=KafkaNodePool+controller+role+KRaft&rows=3&language=en&fq=documentKind:Solution" \
  | python3 -c "
import sys, json
data = json.load(sys.stdin)
docs = data.get('response', {}).get('docs', [])
for d in docs:
    print(d.get('publishedTitle',''))
    print(d.get('view_uri',''))
    print()
"

# Test report-mode diagnosis
curl -sk -X POST \
  https://kafka-diag-app-kafka-diag-agent.apps-crc.testing/api/diagnose-report \
  -F "zip=@/path/to/report.zip" \
  -F "question=analyze the uploaded report and summarize all findings" \
  | python3 -c "import sys,json; r=json.load(sys.stdin); print(r['answer'])"
```

Expected log sequence:
```
Diagnosing from ZIP: 13 files
Extracted issue from kafka YAML message: The Kafka cluster ... is invalid
Using issue query for RAG/KCS: The Kafka cluster ...
RAG query: The Kafka cluster ...
Pre-fetched RAG context for: ...
KCS search: The Kafka cluster ...
Pre-fetched KCS context for: ...
Analyzing uploaded report — aspect: summary
Analyzing uploaded report — aspect: kafka
...
```
