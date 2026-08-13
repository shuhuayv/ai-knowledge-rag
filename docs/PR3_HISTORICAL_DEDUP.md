# PR-3 Historical Dedup（历史重复数据治理）设计文档

- 项目：方达 Java/AI 实习准备（ai-knowledge-rag）
- 分支：feature/content-dedup
- 本文档对应 **CODE-ONLY Implementation Gate**：PR-3 真正执行前的代码能力已全部落地，本轮未在真实 MySQL / Qdrant 上执行任何 PR-3 动作。

## 1. Purpose（目的）

把「历史重复数据治理」所需的全部应用代码能力落地，使下一 REAL Execution Gate 可以安全地在真实数据上执行：

1. soft-delete 应用语义一致（读接口 active-only、mutating 拒绝 deleted）；
2. canonical governance selector（VECTOR_COMPLETENESS → STATUS_RANK → CREATED_AT → ID，通用 deterministic，禁止硬编码 doc ID）；
3. Qdrant cleanup 能力（collection-scoped、exact point IDs、幂等重跑、snapshot 能力存在但本轮不创建）；
4. Search/Ask active-document 防御层（overfetch + active filter + 批量查询，禁止 N+1）；
5. HistoricalDedupService / HistoricalDedupRunner（默认关闭、dry-run 默认 true、fail-closed preconditions、单事务、禁止自动 M2）；
6. A–L 12 组自动化测试（Mock / Mockito / MockWebServer 隔离，禁止连 localhost:3307 / 6333）。

## 2. Canonical Governance Rule（D1）

同一 `content_sha256` 的 active 组（`is_deleted = 0`）内，canonical winner 选择顺序**固定不可调整**：

```
VECTOR_COMPLETENESS → STATUS_RANK → CREATED_AT → ID
```

| 维度 | 规则 |
| --- | --- |
| VECTOR_COMPLETENESS | chunk_count > 0 且 vector_record_count >= chunk_count → COMPLETE；否则 INCOMPLETE。COMPLETE 优先。 |
| VECTOR_INVENTORY_ANOMALY | vector_record_count > chunk_count，或同 chunk 存在重复 vector record → **PR-3 fail closed（HARD FAIL）**。 |
| STATUS_RANK | INDEXED=0 / PARSED=1 / UPLOADED=2 / FAILED=3 / unknown=4（数值小者优先）。 |
| CREATED_AT | 更早者优先（null 排最后）。 |
| ID | 更小者优先（最终确定性 tie-breaker）。 |

实现：`com.shuhuayv.rag.dedup.CanonicalDocumentSelector`（纯逻辑，无任何 doc id 硬编码）。
当前真实 3 组预期（fixture 验证）：A(1,2,4)→2、B(6,7,9,10)→6、C(5,8)→5，即 **CURRENT_FIXTURE_CANONICALS=2,6,5**。

> 职责边界（D6）：上传热路径 `findPreferredActiveDuplicate`（statusRank→createdAt→id，只查 MySQL，禁访 Qdrant）与 governance 冷路径 selector **分开实现**，不混用。

## 3. Field Semantics（D2 / D3 / D4）

| 列 | winner（canonical） | duplicate |
| --- | --- | --- |
| is_deleted | 0（active） | 自身 document id（软删 marker） |
| canonical_document_id | NULL（不写） | winner.id |
| dedup_batch | NULL（不写） | 批次 ID（`dedup-YYYYMMDD-NN`，<=32，字符受控） |

- **禁止** `is_deleted = 1` 统一 marker（与 M2 `UNIQUE(content_sha256, is_deleted)` 语义冲突）。
- **刻意不使用 MyBatis-Plus `@TableLogic`**（假定 0/1 常量，与本语义不符）。
- 常量：`SoftDeleteSemantics.ACTIVE_FLAG = 0L`，`deletedMarker(documentId) = documentId`。
- batchId 由 `DedupBatchIdGenerator` 运行时生成或接收外部固定值；代码**不硬编码**任何具体批次号。

## 4. Runner Safety Switches（双保险）

| 配置 | 默认 | 语义 |
| --- | --- | --- |
| `app.migration.historical-dedup`（APP_MIGRATION_HISTORICAL_DEDUP） | false | 类级 `@ConditionalOnProperty(havingValue="true")` + `run()` 内 `enabled` 二次判断 |
| `app.migration.historical-dedup-dry-run`（APP_MIGRATION_HISTORICAL_DEDUP_DRY_RUN） | true | dry-run 只计算计划，绝不写 DB / Qdrant |

只有 `enabled=true AND dry-run=false` 才可能真实写入。执行时输出 `PR3_ENABLE` / `PR3_DRY_RUN` 审计位。

## 5. Preconditions（fail-closed，真实执行前检查）

| # | 检查 | 不满足 |
| --- | --- | --- |
| ① | M1 四列存在（content_sha256 / is_deleted / canonical_document_id / dedup_batch） | HARD FAIL |
| ② | M2 唯一索引 `uk_document_content_sha256_is_deleted` **不存在** | HARD FAIL |
| ③ | active NULL hash = 0 | HARD FAIL |
| ④ | active dup groups > 0（=0 clean no-op 不报错） | — |
| ⑤ | 已有 canonical_document_id / dedup_batch 非 NULL 的 active dup row（已治理/partial） | HARD FAIL 禁止覆盖 |
| ⑥ | vector inventory anomaly | HARD FAIL |
| ⑦ | 每组恰好 1 winner（selector 确定性保证） | HARD FAIL |
| ⑧ | batchId 合法且 <= 32 | HARD FAIL |

## 6. MySQL Transaction（Phase A）

- **单个 MySQL 事务完成全部组**（`HistoricalDedupTransactionExecutor.canonicalize`，`@Transactional(rollbackFor=Exception.class)`），减少 partial state。
- UPDATE 乐观守卫：`WHERE id=? AND is_deleted=0 AND canonical_document_id IS NULL AND dedup_batch IS NULL`；
  **affected rows 必须 exactly 1**，否则 throw + rollback 当前事务。
- winner 行**不更新**（保持 active、canonical_document_id NULL、dedup_batch NULL）。
- 依赖行（kb_chunk / kb_vector_record）策略：**KEEP（不删不改）**，用于 historical audit / rollback / lineage（PR3_CHILD_ROWS_POLICY=KEEP_FOR_AUDIT）。M2 只作用于 kb_document。

## 7. Qdrant Cleanup Scope（Phase B，D9）

- **collection-scoped**：目标必须来自 duplicate document 的 `kb_vector_record.collection_name`（lineage）；
- **current managed inventory**：配置 `app.dedup.qdrant-managed-collections`（默认 `kb_chunks_zhipu_embedding_3_1024_v1`）；
- 只删除 **exact point IDs**（`deletePoints(collection, pointIds, wait=true)`），禁止 wildcard / broad delete；
- **幂等**：delete exact IDs 已不存在视为 already-clean/success，非 fatal；
- MySQL 成功 + Qdrant 失败：DB 保持治理状态，**不自动反向恢复**；记录 `PR3_QDRANT_CLEANUP_PENDING`，允许仅重跑 cleanup 阶段。

## 8. Mock Legacy Exclusion（D9）

`kb_chunks`（384D，5pts，doc 6-10，chunkId 11-15 无 lineage）= **LEGACY_MOCK_ORPHAN_COLLECTION_STATE**。
PR3_MOCK_LEGACY_POINT_CLEANUP=**NO**（无完整 DB lineage、无法精确恢复、PR-3 只治理 current REAL）。
PR-3 后仅治理 current REAL：**TOTAL 14→10（Zhipu 9→5、Mock 5→5）**。禁止写 14→9 或 14→6。

## 9. Rollback & Snapshot Design

- **软删 tombstone**：duplicate 行保留（is_deleted=自身 id + canonical_document_id + dedup_batch），
  提供完整审计与回滚依据；child rows KEEP 提供 lineage；
- **物理文件**：默认 `PHYSICAL_FILE_DELETE_ON_SOFT_DELETE=NO`（保留原始文件保 rollback；
  代码留 cleanup hook，但本轮不得删真实文件；若产品必须删物理文件须先报告主控 AI）；
- **Qdrant Snapshot**：PR3_REQUIRES_QDRANT_SNAPSHOT=YES；本轮 **CODE-ONLY 不得真实创建 snapshot**。
  代码确保 snapshot 能力存在并可调用（`QdrantSnapshotService.createSnapshot`），
  且真实 cleanup 前会按 `app.dedup.qdrant-snapshot-required-before-cleanup`（默认 true）为 managed collection 创建 snapshot；
- 下一 REAL Execution Gate 在删 REAL Zhipu points 前**必须**为 `kb_chunks_zhipu_embedding_3_1024_v1` 创建 snapshot；
  本轮不要求为 kb_chunks 建 PR-3 snapshot；不得删除现有 snapshot。

## 10. Expected Post-state（真实执行后）

| 项 | 值 |
| --- | --- |
| canonical documents | 2、6、5（active，canonical_document_id=NULL，dedup_batch=NULL） |
| duplicate documents | 1、4、7、9、10、8（is_deleted=自身 id，canonical_document_id=winner，dedup_batch=batch） |
| Zhipu points | 9 → 5（duplicate 7/8/9/10 各 1 point retired；1/4 无 zhipu point） |
| Mock legacy points | 5 → 5（不动） |
| TOTAL Qdrant points | 14 → 10 |
| M2 | 不执行（PR3_AUTO_M2=NO） |

## 11. M2 Separation

PR-3 完成 → stop → evidence → master review → **单独 M2 Gate**。PR3_AUTO_M2=**NO**。
PR-3 前置条件②要求 M2 唯一索引不存在（存在 → HARD FAIL）。

## 12. Application Semantics（D5 / D7 / D10）

- 读接口 active-only：`listDocuments` / `pageDocuments` / `getDocumentById` 只返回 `is_deleted=0`；
  `getDocumentById(deletedId)` → not found；
- mutating fail-closed：`parse` / `index` 对 `is_deleted != 0` 拒绝；
- `deleteDocument`：物理删除 → soft delete（保留 row 作 tombstone，Qdrant 补偿清理，物理文件默认不删）；
- Search/Ask 防御层：`candidateTopK = min(overfetchMaximum, max(K, K*multiplier))`（默认 3 倍 / 上限 50），
  最终返回前对 unique document IDs 一次批量 `findActiveDocumentIds`（禁止 N+1），deleted 不得进入 references/prompt。

## 13. 本轮执行纪律

- 真实历史数据完全不变（REAL_PR3_EXECUTION_TRIGGERED=NO / DATABASE_DATA_MODIFIED=NO /
  QDRANT_WRITE_EXECUTED=NO / QDRANT_SNAPSHOT_CREATED=NO / M2_EXECUTED=NO）；
- dry-run 逻辑验证用 unit tests + mock fixtures（模拟真实 3 组结构），不连接真实 DB 启动 Runner；
- 测试全部 Mock / Mockito / MockWebServer 隔离（REAL_DATABASE_WRITE_FROM_TESTS=NO / REAL_QDRANT_WRITE_FROM_TESTS=NO）。
