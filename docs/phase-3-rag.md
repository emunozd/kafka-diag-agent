# Phase 3 — RAG with Documentation PDFs
## Kafka Diagnostic Agent

> **Goal**: Index the official Red Hat AMQ Streams documentation PDFs into ChromaDB
> and wire the RAGQueryTool so the agent can enrich its diagnoses with accurate,
> version-specific knowledge from the official docs.
>
> **Result**: The agent now combines live cluster data (Phase 2) with documentation
> context (Phase 3) to produce richer, more accurate recommendations.

---

## What was built

| File | Status |
|------|--------|
| `rag/EmbeddingClient.java` | Implemented — was a stub |
| `rag/ChromaDBClient.java` | Implemented — was a stub |
| `rag/PDFIndexer.java` | Implemented — was a stub |
| `tools/RAGQueryTool.java` | Implemented — was a stub |

No new files, no new structure — only the Phase 2 stubs were filled in.

---

## Architecture

```
App startup (virtual thread)
    ↓
PDFIndexer.indexAll()
    ↓
scan /pdfdata/**/  ← version subfolders (e.g. streams-3.1/)
    ↓
for each PDF:
    compute SHA-256
    check hash in ChromaDB (__sha256::<filename>)
    if hash matches → skip (no re-indexing)
    if new or changed:
        extract text page by page (PDFBox 3.x)
        split into chunks (600 words, 80 word overlap)
        for each chunk:
            EmbeddingClient.embed(chunk) → float[768]
            collect into batch of 20
        ChromaDBClient.insertChunks(batch)
        ChromaDBClient.saveFileHash(filename, sha256)
    ↓
LOG: "PDF indexing complete — indexed=18 skipped=0 failed=0"

On subsequent startups:
LOG: "PDF indexing complete — indexed=0 skipped=18 failed=0"
```

---

## Key design decisions

### SHA-256 tracking — atomic with the index
The SHA-256 hash of each PDF is stored as a special document inside the same
ChromaDB collection with id `__sha256::<filename>`. This means:
- If ChromaDB is wiped, the hashes disappear with the data → everything is re-indexed automatically
- No external state file to manage
- Hash is only saved **after** successful indexing — partial failures don't leave stale hashes

The hash document uses a zero vector of 768 dimensions (matching the collection's
embedding dimension) since ChromaDB 1.0.0 requires embeddings in all `/add` calls.

### Chunk size
- Target: 600 words per chunk
- Overlap: 80 words between consecutive chunks
- Minimum: 50 words (smaller chunks are discarded)
- Text is truncated to 900 characters before embedding to stay under the
  nomic-embed-text-v1.5 limit of 512 tokens

### top-k = 2
The RAGQueryTool returns 2 chunks per query. This was reduced from 5 after
hitting the LLM's 4096 token context limit when combining RAG context +
system prompt + tool call history.

### HttpURLConnection instead of HttpClient
Java's `HttpClient` had issues sending request bodies correctly when running
inside Quarkus virtual threads. Replaced with `HttpURLConnection` which
works reliably in this environment.

---

## ChromaDB 1.0.0 specifics

ChromaDB 1.0.0 changed the API significantly from earlier versions:

| Operation | Correct URL |
|-----------|-------------|
| Create collection | `POST /api/v2/tenants/default_tenant/databases/default_database/collections` |
| Get collection | `GET /api/v2/tenants/default_tenant/databases/default_database/collections/<name>` |
| Upsert chunks | `POST /api/v2/tenants/default_tenant/databases/default_database/collections/<id>/upsert` |
| Add hash doc | `POST /api/v2/tenants/default_tenant/databases/default_database/collections/<id>/add` |
| Get by ID | `POST /api/v2/tenants/default_tenant/databases/default_database/collections/<id>/get` |
| Query | `POST /api/v2/tenants/default_tenant/databases/default_database/collections/<id>/query` |

> **Note**: `/api/v1/` is deprecated. `/api/v2/collections/` without tenant/database returns 404.
> All endpoints require the full tenant/database path.

---

## PDFBox 3.x migration

PDFBox 3.x removed `PDDocument.load()` entirely. The correct API is:

```java
// PDFBox 2.x (removed)
PDDocument doc = PDDocument.load(file);

// PDFBox 3.x (correct)
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessReadBufferedFile;

PDDocument doc = Loader.loadPDF(new RandomAccessReadBufferedFile(file));
```

---

## Lessons learned

| Problem | Cause | Fix |
|---------|-------|-----|
| HTTP 400 `body=None` on all embedding calls | `HttpClient` not sending body in Quarkus virtual threads | Replaced with `HttpURLConnection` |
| ChromaDB 404 on collection create/query | ChromaDB 1.0.0 requires full tenant/database path | Added `COLLECTIONS_BASE` constant with full path |
| ChromaDB 422 `missing field embeddings` | `/upsert` requires embeddings in ChromaDB 1.0.0 | Used `/add` with 768-dimensional zero vector for hash docs |
| ChromaDB 400 `dimension mismatch` | Hash doc used `[0.0]` (dim=1) but collection expects dim=768 | Zero vector of exactly 768 dimensions |
| Embedding 400 `context length exceeded` | 600 words ≈ 600-700 tokens, model limit is 512 | Truncate input to 900 characters before embedding |
| LLM 400 `6282 input tokens` | 5 RAG chunks + system prompt + tool history exceeded 4096 | Reduced top-k to 2, max-tokens to 512 |
| Re-indexing on every build | `saveFileHash` was failing silently, hashes never saved | Fixed hash save + manual ChromaDB collection reset once |
| `Thread.ofVirtual()` compile error | S2I image was using Java 17 | Updated BuildConfig to `registry.redhat.io/ubi9/openjdk-21:latest` |
| `PDDocument.load(File)` compile error | PDFBox 3.x removed all `load()` methods from `PDDocument` | Replaced with `Loader.loadPDF(new RandomAccessReadBufferedFile(file))` |

---

## One-time setup — resetting a corrupted ChromaDB collection

If the collection has corrupted data from failed indexing attempts, delete it
and let the indexer rebuild it on next startup:

```bash
LLM_POD=$(oc get pod -n kafka-diag-agent \
  -l serving.kserve.io/inferenceservice=qwen3-8b-llm \
  -o jsonpath='{.items[0].metadata.name}')

oc exec -n kafka-diag-agent $LLM_POD -- \
  curl -s -X DELETE \
  http://chromadb.kafka-diag-agent.svc.cluster.local:8000/api/v2/tenants/default_tenant/databases/default_database/collections/kafka-knowledge
```

Then restart the app pod — the indexer will rebuild everything from scratch.

---

## Validation

After a clean indexing run:

```
PDF indexing complete — indexed=18 skipped=0 failed=0
```

On all subsequent startups (no re-indexing):

```
PDF indexing complete — indexed=0 skipped=18 failed=0
```

Test query combining live cluster data + RAG documentation:

```bash
curl -sk https://kafka-diag-app-kafka-diag-agent.apps-crc.testing/api/diagnose \
  -H "Content-Type: application/json" \
  -d '{"question":"how can I tune kafka for better performance and throughput","namespace":"kafka-ns"}' \
  | python3 -c "import sys,json; r=json.load(sys.stdin); print(r['answer'])"
```

Expected: structured response with consumer/producer tuning recommendations,
Cruise Control guidance, and partition management advice — all sourced from
the official Red Hat Streams 3.1 documentation.
