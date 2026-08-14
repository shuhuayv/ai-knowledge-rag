# Final Engineering Facts — ai-knowledge-rag

> 本文档只记录**最终可验证事实**。所有数字来自上一 Closure Gate 已接受状态与本轮 live 复核。
> 分类：`CURRENT VERIFIED` / `HISTORICAL VERIFIED` / `ACCEPTED WITH CAVEAT` / `TECHNICAL DEBT` / `BOUNDARIES`。
> 不含任何 secret / 私人文路径 / 私人文文件名 / 文档正文 / chunk 内容 / 完整 Ask/Search 响应 / Qdrant 向量 / 本机用户名 / 私网或本地 IP。

---

## 1. Git / PR 主线（CURRENT VERIFIED）

- repository：`ai-knowledge-rag`（本地仓库根目录，以下简称 `<REPO_ROOT>`；remote `git@github.com:<owner>/ai-knowledge-rag.git`）
- PR #6：**MERGED**
- audited feature commit：`5e248500e83940e09aaeec68365b147352e90847`
- accepted merge commit：`8366061ed5eb24bdec4281339b31ad19fb4afa73`
- feature commit 为 main ancestor：是
- 本 Gate 开始时 live recheck：`git fetch origin` 后 `origin/main == 8366061`，无后续新 commit，worktree clean。

## 2. Tests / Build（CURRENT VERIFIED）

- **202 Maven tests，0 failures / 0 errors / 0 skipped**
- `mvn -B package -DskipTests` → **BUILD SUCCESS**
- 测试覆盖：Embedding 双模式、CollectionNameResolver、Qdrant 向量读写、检索、RAG 问答、Embedding 状态接口、限流退避（见 `src/test`）

## 3. MySQL（CURRENT VERIFIED）

- database：`ai_knowledge_rag`
- `kb_document`：total=10，active=4（IDs **[2,3,5,6]**），soft-deleted=6（IDs [1,4,7,8,9,10]）
- active NULL `content_sha256`：0
- active duplicate groups：0
- M2 unique index：`uk_document_content_sha256_is_deleted` = `UNIQUE(content_sha256, is_deleted)`
- canonical governance rule：`VECTOR_COMPLETENESS → STATUS_RANK → CREATED_AT ASC → ID ASC`
- historical canonical winners：[2,6,5]
- historical duplicate mapping：1→2，4→2，7→6，8→5，9→6，10→6
- dedup_batch：`dedup-20260813-01`
- soft-delete rule：active row `is_deleted=0`；historical duplicate `is_deleted=self_document_id`；canonical row `canonical_document_id=NULL`、`dedup_batch=NULL`、`is_deleted=0`

## 4. Qdrant（CURRENT VERIFIED）

- active persistent container：`qdrant-rag-persistent-20260810`
- REAL collection：`kb_chunks_zhipu_embedding_3_1024_v1`，维度 **1024**，REAL points **5**
- legacy Mock collection：`kb_chunks`，维度 **384**，Mock points **5**
- 合计：**10** points
- rollback container：`qdrant-rag`，状态 **STOPPED retained**
- legacy Mock retirement：**NOT DONE**，分类 **SEPARATE_TECH_DEBT**

## 5. Historical Qdrant Cleanup（HISTORICAL VERIFIED）

- PR-3 REAL cleanup 只治理 current REAL managed collection：`kb_chunks_zhipu_embedding_3_1024_v1`
- PRE：9 REAL points → POST：5 REAL points
- exact retired points 对应 historical duplicate documents：7、8、9、10
- MySQL soft-deleted 6 rows，但 REAL Qdrant 只 retire 4 points（docs 1、4 无 current REAL vector record，设计预期，非漏删）
- cleanup：exact Point IDs only；禁止 wildcard / broad payload delete / collection delete
- historical cleanup：snapshot-before-delete safety
- legacy Mock：不自动治理

## 6. Current REAL Runtime（CURRENT VERIFIED）

- REAL Embedding：provider **zhipu**，model **embedding-3**，dimension **1024**，mode **REAL**，fallback **false**
- REAL Chat：provider **zhipu**，model **glm-4.5-air**
- 当前 REAL 17-case generation：17/17 使用 provider=zhipu / model=glm-4.5-air / embeddingProvider=zhipu / embeddingMode=REAL / fallbackUsed=false
- current references：active document only（IDs [2,3,5,6]）

## 7. Evaluation（HISTORICAL VERIFIED vs CURRENT VERIFIED）

### Historical 17-case baseline（去重前）

- 17 samples（16 retrieval + 1 no-answer）
- Hit@1=0.5 / Hit@3=0.5 / Hit@5=1.0 / MRR=0.6 / No-answer rejection=1.0
- retrieval latency：avg=1044.82 / P50=1056.0 / P95=1463.06 ms
- generation：success=1.0 / reference inclusion=1.0 / faithfulness heuristic=0.875
- generation latency：avg=2596.51 / P50=2510.6 / P95=3799.0 ms

### Current post-dedup 17-case result（受控同 17 样本复测）

- Hit@1=0.5 / Hit@3=**1.0** / Hit@5=1.0 / MRR=**0.75** / No-answer rejection=1.0
- retrieval latency：avg=1570.13 / P50=1337.5 / P95=2492.78 ms
- generation：success=1.0 / reference inclusion=1.0 / faithfulness heuristic=**0.9375**
- generation latency：avg=3267.92 / P50=2953.7 / P95=5049.7 ms

### 正确表述边界（ACCEPTED WITH CAVEAT on claims）

- 可写：controlled 17-case regression benchmark 上 Hit@3 0.5→1.0、MRR 0.6→0.75、faithfulness 0.875→0.9375；Hit@1/Hit@5/no-answer 不变；retrieval ranking quality improved，**与历史重复污染减少一致**。
- 不可写：RAG accuracy=100% / production accuracy=100% / 准确率提高 100% / PR-3 必然导致模型准确率提高 / production performance improved。
- 必须诚实：latency 在本轮 run **regressed**，且 latency 变化**不应直接归因于 dedup**。

### Per-case ranking（CURRENT VERIFIED）

- 16 retrieval cases 中：8 cases ranking changed、8 cases Hit@3 improved、8 cases MRR improved、0 regression observed in verified comparison。
- 仍只能称 **controlled per-case benchmark result**，非 production measurement。

## 8. RAG Identity 设计（CURRENT VERIFIED）

- Document-level identity：`content_sha256 = SHA-256(raw uploaded file bytes)`。严禁写成 normalized text hash / PDF extracted text hash / chunk text hash / filename+content hash / path+content hash。
- Upload duplicate lookup：`content_sha256 + is_deleted=0`，阻止新的 active duplicate document。
- Point-level identity：Qdrant deterministic point ID，source `documentId:chunkId:indexVersion` → deterministic UUID v3，保证 upsert identity / idempotency。
- `content_sha256` 与 Qdrant point ID 是**两套不同** identity。

## 9. Historical Dedup 最终设计（CURRENT VERIFIED）

- PR-3 canonical rule：`VECTOR_COMPLETENESS → STATUS_RANK → CREATED_AT ASC → ID ASC`
- Vector completeness：`chunk_count > 0` 且 `vector_record_count == chunk_count`；若 `vector_record_count > chunk_count` 或 duplicate vector records 出现 → anomaly，fail closed。
- Status rank：`INDEXED > PARSED > UPLOADED > FAILED > unknown`
- historical duplicate rows：soft delete（非物理删除）；`kb_chunk` / `kb_vector_record` KEEP_FOR_AUDIT；normal reads/parse/index active-only。
- Search：Qdrant 结果中 `documentId=null` / unknown / inactive / deleted 全部 fail closed；采用 bounded overfetch + batched active-document lookup，避免 N+1 与 stale point 占满 topK。

---

## 10. Caveats（必须保留，不得洗白）

### PR-3 caveat（ACCEPTED WITH CAVEAT）

- `PR3_DATA_POSTSTATE=PASS`
- 但 `PR3_PROCEDURAL_EVIDENCE=PARTIAL`：部分 historical snapshot / runner completion raw evidence 未完整保存。
- 表述：可写「最终 schema/data/app compatibility verified」；不可写「整个 M2 Gate 全程严格零额外 DB write」——历史 M2 Gate 曾发生临时 DB account create/drop（该账号最终已删除、不持久），属当轮未明确授权的额外 DB account write。

### M2 caveat（ACCEPTED WITH CAVEAT）

- DATA / SCHEMA / APP compatibility = PASS
- 但历史 M2 Gate 曾发生临时 DB account create/drop（账号最终已删除、不持久）。
- 可写：最终 schema/data/app compatibility verified；历史过程存在已记录 procedural deviation。

### REAL Evaluation `ai_call_log` caveat（ACCEPTED WITH CAVEAT / 永久保留）

- REAL `/api/rag/ask` 当前会正常持久化 `ai_call_log`（append-only runtime audit history）。
- 上一 REAL benchmark 过程中产生 **18 条 successful append-only audit log inserts**；属 runtime audit history，**非** corpus mutation。
- Closure 已确认：未观察到 `kb_document` / `kb_chunk` / `kb_vector_record` / Qdrant managed data mutation。
- 但上一 Prompt 错误要求 `DATABASE_WRITE_EXECUTED=NO` → `PREVIOUS_REAL_EVAL_PROCEDURAL_RESULT=FAILED_CONSTRAINT`。
- 技术结果保留；procedural caveat 永久保留。
- 未来 REAL benchmark rule 应改为：允许预期的 `ai_call_log` append-only writes，同时校验 pre count / post count / expected delta / corpus invariants / Qdrant invariants；不得再写「整个 REAL Ask benchmark 必须 MySQL 零写」。

### legacy Mock technical debt（TECHNICAL DEBT）

- legacy Mock collection `kb_chunks`（384 维，5 points）**未清理**，不属于当前 RAG 主线 blocker。
- README 如实说明 Mock / REAL 物理分离；**不得写** legacy Mock 已清理。

## 11. Boundaries（BOUNDARIES）

- 这是 **local / controlled development project**，**不是** production SaaS。
- 未验证：high concurrency、multi-tenant、large-scale user traffic、production SLA、real-world accuracy。
- 不得为简历「显得厉害」虚构：高并发 QPS、生产用户数、线上准确率、SLA、大规模企业部署、百万文档、微服务集群、Kubernetes。

## 12. 本 Gate 状态位（冻结时快照）

- `RAG_ENGINEERING_STATUS=CLOSED_WITH_MINOR_DOCUMENTATION_TASKS` → 本 Gate 后预期 `FROZEN_READY_FOR_INTERNSHIP_PORTFOLIO`
- source code 零修改；DB 零写；Qdrant 零写；REAL AI 零调用；benchmark 零重跑；M1/Backfill/PR-3/M2 零重跑。
