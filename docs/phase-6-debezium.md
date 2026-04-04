# Phase 6 — Debezium CDC Diagnostics + Final Polish
## Kafka Diagnostic Agent

> **Goal**: Implement real Debezium connector diagnostics, extend the RAG knowledge
> base with official Debezium documentation, reorganize the PDF folder structure,
> and polish the agent's response quality.
>
> **Result**: The agent can now diagnose Debezium CDC connectors in both live cluster
> mode and report mode, backed by the official Red Hat build of Debezium 3.2.7
> documentation indexed in ChromaDB.

---

## What was built

| File | Change |
|------|--------|
| `tools/DebeziumTool.java` | Full implementation — Debezium connector detection and diagnostics |
| `rag/PDFIndexer.java` | Updated `extractVersion` for new folder structure |
| `rag/PDFWatcher.java` | Increased startup delay to `intervalSeconds * 10` |
| `tools/ReportUploadTool.java` | Improved `buildSummary` and `mapAspectToPrefix` |
| `agent/KafkaDiagnosticAgent.java` | Updated `@SystemMessage` with Debezium and no-hallucination rules |

---

## PDF Knowledge Base Structure

The knowledge base was reorganized from flat versioned folders to a product/version hierarchy:

```
/pdfdata/
├── streams/
│   └── 3.1/
│       ├── Red_Hat_Streams_for_Apache_Kafka-3.1-*.pdf  (18 files)
│       └── ...
└── debezium/
    └── 3.2.7/
        ├── Red_Hat_build_of_Debezium-3.2.7-Debezium_User_Guide-en-US.pdf
        ├── Red_Hat_build_of_Debezium-3.2.7-Getting_Started_with_Debezium-en-US.pdf
        ├── Red_Hat_build_of_Debezium-3.2.7-Installing_Debezium_on_OpenShift-en-US.pdf
        └── Red_Hat_build_of_Debezium-3.2.7-Release_Notes_for_Red_Hat_build_of_Debezium_3.2.7-en-US.pdf
```

### Version metadata

The `extractVersion` method in `PDFIndexer` now extracts the full product/version path:

| Path | Extracted version |
|------|-------------------|
| `/pdfdata/streams/3.1/doc.pdf` | `streams/3.1` |
| `/pdfdata/debezium/3.2.7/doc.pdf` | `debezium/3.2.7` |

This allows the RAG citations to show `v[streams/3.1]` or `v[debezium/3.2.7]` clearly.

### Uploading new PDFs dynamically

Thanks to the PDF Watcher implemented in Phase 5, new PDFs are indexed automatically:

```bash
CHROMA_POD=$(oc get pod -n kafka-diag-agent -l app=chromadb -o jsonpath='{.items[0].metadata.name}')

# Create folder structure
oc exec -n kafka-diag-agent $CHROMA_POD -- mkdir -p /pdfdata/debezium/3.2.7

# Copy PDFs
for pdf in /local/path/debezium/*.pdf; do
  oc cp "$pdf" kafka-diag-agent/$CHROMA_POD:/pdfdata/debezium/3.2.7/
done
```

**Note**: The PDF PVC (`pvc-pdf-knowledge`) persists across `crc stop/start`.
The vector store data (ChromaDB) does NOT persist — it re-indexes on each CRC restart.
This is a known CRC hostpath provisioner limitation.

---

## Debezium Tool

### Detection logic

The `DebeziumTool` identifies Debezium connectors by checking the `connector.class`
field in the KafkaConnector spec config against known Debezium class prefixes:

```java
private static final List<String> DEBEZIUM_CLASSES = List.of(
    "io.debezium.connector.mysql",
    "io.debezium.connector.postgresql",
    "io.debezium.connector.sqlserver",
    "io.debezium.connector.mongodb",
    "io.debezium.connector.oracle",
    "io.debezium.connector.db2",
    "io.debezium.connector.mariadb",
    "io.debezium"
);
```

### Configuration checks

For each detected Debezium connector, the tool validates:

| Check | Condition |
|-------|-----------|
| `database.hostname` | Must be present (or `mongodb.hosts` for MongoDB) |
| `topic.prefix` | Required for event routing (replaces `database.server.name`) |
| Schema history topic | Required for MySQL, SQL Server, Oracle |
| `tasks.max` | Must be `1` — Debezium does not support parallel tasks |
| `snapshot.mode` | Reported for awareness |

### Report mode

When analyzing an uploaded Strimzi report, the agent calls:
- `analyzeUploadedReport("connect")` → reads `kafkaconnects/` files
- `analyzeUploadedReport("connector")` → reads `kafkaconnectors/` files

The Strimzi `report.sh` must be run with `--connect=<connect-name>` to include
KafkaConnect resources:

```bash
curl -s https://raw.githubusercontent.com/strimzi/strimzi-kafka-operator/main/tools/report.sh \
  | bash -s -- \
    --namespace=my-namespace \
    --cluster=my-kafka-cluster \
    --connect=my-connect-cluster
```

---

## Report mode improvements

### buildSummary enhancement

The summary now explicitly lists key Kafka resources found in the report,
so the agent knows exactly what to query:

```
Key resources detected:
  - kafkas/kafka-cachebh.yaml
  - kafkaconnects/debezium-kafka-connect-cluster.yaml
  - kafkaconnectors/cachebh-topic.yaml
  - kafkatopics/topic-test.yaml
  - kafkanodepools/kafka-node-pool-broker.yaml
  - kafkanodepools/controller.yaml
  - events/events.txt
```

### mapAspectToPrefix ordering

`connector` is checked before `connect` to avoid prefix conflicts:

```java
if (aspect.contains("connector"))  return "kafkaconnectors/";
if (aspect.contains("connect") && !aspect.contains("mirror")) return "kafkaconnects/";
if (aspect.contains("debezium"))   return "kafkaconnectors/";
```

---

## PDF Watcher fix

The watcher startup delay was increased from `2×` to `10×` the scan interval
to prevent it from competing with the initial indexing run on startup:

```java
scheduler.scheduleAtFixedRate(
        this::scan,
        intervalSeconds * 10,  // 600s — gives initial indexing time to complete
        intervalSeconds,
        TimeUnit.SECONDS
);
```

With 22 PDFs (including the 1011-page Debezium User Guide), the initial indexing
takes ~25 minutes. Without this fix, the watcher would start a parallel indexing
run at 2 minutes, doubling the work and causing ChromaDB conflicts.

---

## Lessons learned

| Problem | Cause | Fix |
|---------|-------|-----|
| PDFs not re-indexed after folder rename | SHA-256 keys use filename only, not path | Delete ChromaDB collection + re-index |
| Watcher competing with initial indexer | Startup delay too short (2× interval) | Increased to 10× interval |
| Agent says "no kafka files found" | `buildSummary` didn't list individual files | Added explicit key resource listing |
| `kafkaconnectors/` not found when asking "connect" | `connect` matched before `connector` | Reordered checks: `connector` before `connect` |
| PVC read-only in app pod | `readOnly: true` mount in Deployment | Use ChromaDB pod for filesystem operations |

---

## Validation

### Live mode — Debezium query
```bash
curl -sk https://kafka-diag-app-kafka-diag-agent.apps-crc.testing/api/diagnose \
  -H "Content-Type: application/json" \
  -d '{"question":"how do I configure a debezium postgresql connector","namespace":"kafka-ns"}' \
  | python3 -c "import sys,json; r=json.load(sys.stdin); print(r['answer'])"
```

Expected: RAG returns chunks from `Red_Hat_build_of_Debezium-3.2.7-Debezium_User_Guide-en-US.pdf`
with PostgreSQL connector configuration details.

### Report mode — with Connect
```bash
# Generate report with --connect flag
curl -s https://raw.githubusercontent.com/strimzi/strimzi-kafka-operator/main/tools/report.sh \
  | bash -s -- \
    --namespace=my-ns \
    --cluster=my-cluster \
    --connect=my-connect

# Upload and analyze
curl -sk https://kafka-diag-app-kafka-diag-agent.apps-crc.testing/api/diagnose-report \
  -F "zip=@report-*.zip" \
  -F "question=analyze the kafka connect and debezium connectors in this report" \
  | python3 -c "import sys,json; r=json.load(sys.stdin); print(r['answer'])"
```

### PDF indexing verification
```bash
oc logs -n kafka-diag-agent deployment/kafka-diag-app | grep "indexed\|skipped"
# Expected: indexed=0 skipped=22 failed=0 (after initial run)
```
