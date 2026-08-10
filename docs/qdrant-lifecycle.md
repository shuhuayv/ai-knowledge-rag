# Qdrant Lifecycle Operations

Safe lifecycle operations added on top of the existing Qdrant vector service
(`QdrantVectorService` / `QdrantVectorServiceImpl`). Pure additive — no existing
method signature or behavior was changed.

## New methods (contract summary)

| Method | Returns | Notes |
|---|---|---|
| `deletePoints(coll, pointIds, wait)` | `QdrantOperationResult` | UUID v3 / v4 both deletable; input de-duplicated (`LinkedHashSet`) |
| `deletePointsByDocumentId(coll, documentId, wait)` | `QdrantOperationResult` | filter by `documentId` |
| `countPoints(coll)` | `long` | total point count |
| `countPointsByDocumentId(coll, documentId)` | `long` | count filtered by `documentId` |
| `scrollPoints(coll, offset, limit)` | `ScrollPage` | `with_payload=true`, `with_vector=false` |
| `collectionExists(coll)` | `boolean` | read-only, no side effect |
| `listCollections(namePrefix)` | `List<String>` | prefix filter |

### Hard constraints

1. **UUID v3 / v4 treated equally.** Point IDs are validated by strict regex
   (`^[0-9a-fA-F]{8}-…-{12}$`), not by inspecting the version bit. The IDs produced by
   `buildPointId()` (UUID v3 via `nameUUIDFromBytes`) and random UUID v4 are both
   deletable. Invalid IDs (null / blank / non-UUID) are skipped without throwing and
   without triggering a batch-400.
2. **`documentId` filter must be a JSON number.** The payload filter is serialized as
   `{"value": 6}` (a number). Sending `{"value": "6"}` (string) is silently treated as
   **0 hits** by Qdrant — a dangerous inconsistency (delete returns `status=completed`
   but removes nothing). The implementation always emits the numeric form; callers must
   also store `documentId` as a number.
3. **Return value carries `requestedCount` + `acceptedCount`, but no `deletedCount`.**
   `QdrantOperationResult` = `(operationId, status, requestedCount, acceptedCount)`.
   - `operationId` / `status` come from the Qdrant HTTP response.
   - `requestedCount` = size of the caller's original `pointIds` list (0 if null/empty).
   - `acceptedCount` = number of IDs that passed the client-side UUID check **and** were
     de-duplicated (via `LinkedHashSet`), i.e. the count actually sent to Qdrant.
   - The number of deleted points must still be computed by the caller as
     `countBefore - countAfter` — Qdrant's delete response does **not** report a deleted count.
   - **Observability improvement (this round):** previously a request with some invalid IDs
     returned `status=completed` with no signal about how many were filtered. Now
     `requestedCount != acceptedCount` makes "partial invalid IDs silently filtered" visible
     to the caller (e.g. requested=4, accepted=2). When `acceptedCount == 0`, the operation is
     `status=skipped` and issued **no** HTTP request.

4. **Point ID contract is UUID-string only (this round).** `scrollPoints` / `ScrollPage`
   assume Point IDs are UUID strings. Qdrant natively also supports unsigned-integer Point
   IDs, but that form is **out of contract** here: `scrollPoints` does not assume
   `nextOffset` is numeric and performs no numeric offset back-tracing. This component is
   **not** a full generic Qdrant SDK — it serves only this project's existing UUID Point ID
   scenario.

Other rules: `wait=true` makes the delete visible on the next `countPoints` **in the local
single-node Qdrant 1.18.2 smoke** — observed immediately, no sleep/retry needed. This was
**only** verified on a single-node local instance; multi-node / replicated / high-load
visibility is NOT tested and is out of contract. An empty filtered set issues **no HTTP
request**. `deletePointsByDocumentId(null)` throws `IllegalArgumentException` with zero HTTP calls.

## Temp-collection smoke (real Qdrant)

Run only against ephemeral `kb_smoke_tmp_<timestamp>` collections; production
`kb_chunks` (5 pts) and `kb_chunks_zhipu_embedding_3_1024_v1` (9 pts) were never touched
and remained unchanged before/after. Gated by `-Dqdrant.smoke=true`; skipped by default
`mvn test` (CI has no Qdrant).

- `{"value": 900001}` → count = 1 ; `{"value": "900001"}` → count = 0
  (after writing 1 synthetic point with `documentId = 900001`; confirms the JSON-number
  constraint above at the HTTP layer — string form yields 0 hits).
- `wait=true` delete on the local Qdrant 1.18.2 single-node smoke: the post-delete count
  reflects the new state immediately — no sleep or retry needed. **Scope:** verified only
  on this single-node local instance; multi-node / replicated / high-load NOT tested.

## Snapshot gate (BK-1)

`BK-1 = PASS`. Both `kb_chunks` and `kb_chunks_zhipu_embedding_3_1024_v1` snapshots:
create OK (HTTP 200), download OK (HTTP 200), local size > 0, and SHA256 verified
consistent across three independent sources (server `.checksum`, HTTP download,
`docker cp`).

> ⚠️ **No restore drill was performed. `BK-1 = PASS` does NOT mean "recoverable is
> verified".** Only creation + download + integrity were proven.

## Out of scope (this PR)

- `content_sha256` / content hashing
- DB migration / schema changes
- payload / document de-duplication
- soft delete
- `deleteDocument` cascade
- historical / orphan point cleanup
- `ensurePayloadIndex` (deferred to a later round)
