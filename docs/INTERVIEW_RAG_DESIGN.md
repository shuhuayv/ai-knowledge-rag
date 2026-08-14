# Interview RAG Design — 15 Engineering Rationale Q&A

> 面试可讲、工程化、简洁。每条都可独立成一段回答。

## 1. 为什么用 raw bytes hash，而不是 parsed text hash？

文档身份必须稳定且上传即定。用 `SHA-256(raw uploaded file bytes)` 意味着**同一份二进制文件无论怎么重新解析，身份都不变**；若用 parsed text / PDF 提取文本 hash，则解析器版本、空格归一化、OCR 差异都会导致同一文档算出不同身份，破坏幂等上传与去重。原始字节哈希把"文档是什么"与"怎么解析"解耦。

## 2. document identity 和 vector point identity 为什么要分开？

这是**两个不同维度的身份**：
- `content_sha256`（文档级）：回答"这份上传文件是否重复"，用于上传幂等与文档级治理。
- Qdrant point ID（向量点级，`documentId:chunkId:indexVersion` → UUID v3）：回答"这条向量属于哪个 chunk 的哪次索引"，用于向量 upsert 幂等与精确清理。

一份文档会产生多个 chunk、每个 chunk 一条向量；文档身份不能替代向量点身份，否则重切块 / 重索引会造成孤儿向量或误覆盖。

## 3. 重复上传为什么不直接返回 error？

返回 error 会让合法用户在一次误传后永远卡死。正确做法是：用 `content_sha256 + is_deleted=0` 查重，若已存在 preferred active document，**返回已有文档**（幂等），不新增 duplicate。这既防污染，又对用户友好，且天然兼容 soft-delete 后重新激活的场景。

## 4. 为什么 canonical selection 先看 vector completeness？

因为 RAG 的检索质量最终依赖向量是否齐全。规则 `VECTOR_COMPLETENESS → STATUS_RANK → CREATED_AT ASC → ID ASC` 中，第一步要求 `chunk_count > 0` 且 `vector_record_count == chunk_count`；若向量不全（多向量 / 缺向量）直接 fail closed 为 anomaly。先保"可用"，再比"状态好"，最后用时间/ID 做确定性 tie-break，避免随机选主。

## 5. 为什么 soft-delete 用 self document id？

historical duplicate 行 `is_deleted=self_document_id`，canonical 行 `is_deleted=0`。这样**同一份内容在表里只可能有一个 active 副本**（唯一约束 `UNIQUE(content_sha256, is_deleted)` 在 `is_deleted=0` 时强制唯一），重复行全部以"指向自己 id"的方式标记为非 active，既不物理删除（保留审计），又能让活跃查询零歧义。不能统一 `is_deleted=1`，否则会撞唯一约束且丢失"它是谁的重复"的 lineage。

## 6. 为什么 M2 是 `UNIQUE(content_sha256, is_deleted)`？

因为这正是 soft-delete 模型的活跃唯一性锚点：只有 `is_deleted=0` 的那一行需要保证 `content_sha256` 唯一（防止新 active 重复文档），而历史重复行 `is_deleted=self_id` 各不相同，不会冲突。若只对 `content_sha256` 建唯一，则 soft-deleted 的重复行会与 canonical 行冲突，无法共存。

## 7. 为什么不能统一 `is_deleted=1`？

见 Q5：统一 `is_deleted=1` 会让所有重复行拥有相同的 `(content_sha256, 1)`，违反"保留 lineage + 可审计"的目标，也失去了"活跃副本"的判定锚。self-id 软删是"标记非活跃但仍可追溯到自己是哪份内容的重复"的最小代价方案。

## 8. 为什么 Search 要 fail closed？

RAG 最危险的不是"没召回"，而是"召回了不该召回的内容"。Qdrant 返回的 point 若 `documentId=null` / unknown / inactive / deleted，说明向量与 MySQL 业务元数据已经不一致——此时**宁可少召回也不能把脏/失效文档喂给 LLM**。fail closed 是数据治理的最后一道闸。

## 9. 为什么 bounded overfetch + batched active lookup？

TopK 检索直接在 Qdrant 上取 N 条，但其中可能混有 inactive/unknown 文档。bounded overfetch 多取一些候选，再用 **batched**（一次性 `IN` 查询）去 MySQL 校验 active 状态并补齐元数据，既能过滤脏点，又避免逐条查询的 N+1，也防止 stale point 占满 topK 导致结果空洞。

## 10. 为什么 Qdrant cleanup 用 exact Point IDs？

历史去重清理的是**确定已知的** historical duplicate documents（7/8/9/10）对应的向量。用 exact Point ID 列表删除，粒度精确到点，配合 snapshot-before-delete，可审计、可回滚、零误伤。wildcard / payload 范围删除在大批量数据下极易误删无关向量，是禁止项。

## 11. 为什么 legacy Mock 不混进 historical PR-3？

PR-3 治理的是 **current REAL managed collection**（`kb_chunks_zhipu_embedding_3_1024_v1`，1024 维）。legacy Mock collection（`kb_chunks`，384 维）是开发期独立空间，维度与语义都不同，混在一起会污染去重统计与向量完备性判定。它作为独立 tech debt 单独挂账，README 如实说明，不假装已清理。

## 12. 为什么迁移拆成 M1 → compatible code → Backfill → PR-3 → M2？

数据库迁移最忌"一步大改 + 代码不兼容"。顺序保证：M1 只做 additive（加列/加表，不破坏旧代码）→ 部署兼容代码（新旧都能跑）→ Backfill 补原始哈希 → PR-3 做历史去重（依赖 backfill 数据）→ M2 最后加唯一约束（此时数据已干净，加约束不会失败）。每一步都可独立验证、可回退。

## 13. 为什么 benchmark 不能叫 production accuracy？

受控 17-case 基准语料里**本身就含重复演示文档**（`rag-demo.txt` ×4），是用来验证去重/检索逻辑的小型回归集，不代表真实分布、真实并发或真实用户问题。把它稱作 production accuracy 是严重夸大，会误导下游决策。正确表述是"controlled 17-case regression benchmark"。

## 14. benchmark 改善与 causal claim 的边界在哪？

可以因果地说"去重后，受控基准上的检索排序质量改善（Hit@3 0.5→1.0、MRR 0.6→0.75），与历史重复污染减少一致"。**不能**因果地说"去重导致模型准确率提高"——因为生成层 LLM 没变，且 latency 在本轮还 regressed。改善来自检索侧去噪，不是模型侧提升；latency 变化更不能归因于 dedup。

## 15. `ai_call_log` side effect 给 Gate 设计带来什么教训？

上一轮 REAL benchmark 的 Gate 错误要求"数据库零写"，但 `/api/rag/ask` 本就会正常持久化 `ai_call_log`（append-only 审计历史，非语料变更）。结果 18 条审计写入被误判为违规，逼出了对"零写"定义的重新审视。**教训**：Gate 的约束必须区分"预期内的副作用（审计日志）"与"意外的语料/状态变更"；对账要做双侧交叉验证（日志侧 + 数据库侧，差额为 0），并显式允许预期审计写入，否则会把合规的正确行为误报成违规。
