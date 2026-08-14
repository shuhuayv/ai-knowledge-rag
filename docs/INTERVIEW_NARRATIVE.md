# Interview Narrative — ai-knowledge-rag

> 三个长度版本。核心原则：讲清"这不是简单调 API"，讲清数据治理与工程纪律。

---

## 30 秒版本

我们做了一个 Spring Boot + RAG 的 AI 知识库问答系统：上传文档、切块、真实向量化（智谱 embedding-3）、Qdrant 检索、再让 LLM 生成带引用的答案。最亮点的工程问题不是"接模型"，而是**数据治理**——怎么给文档定身份、怎么在不破坏线上数据的前提下清理历史重复文档、以及怎么让 Gate 约束不误伤合规的副作用。最终 202 个测试全绿，受控基准上检索质量明显提升。

## 90 秒版本

项目分四块讲。第一，上传与身份：用 `SHA-256(raw bytes)` 给文档定身份，重复上传幂等返回已有文档，不新增重复。第二，切块与向量：滑动窗口切块、真实 embedding-3 写入独立 Qdrant Collection，和 Mock 物理隔离。第三，检索与 RAG：Search 对所有 unknown/inactive/deleted 文档 fail closed，用 bounded overfetch + 批量 active 校验过滤脏点；RAG 生成带 references。第四，治理：历史重复文档用 canonical 规则选主、soft-delete 自引用 + 唯一约束保证只留一个 active 副本；迁移走 M1→兼容代码→Backfill→PR-3→M2 的分闸顺序。最后做了受控 17-case 评估，去重后 Hit@3 0.5→1.0、MRR 0.6→0.75。

## 3 分钟版本（重点讲"为什么不是简单调 API"）

1. **historical duplicate pollution**：真实语料里同一份文档被多次上传，产生重复 active 行与孤儿向量。RAG 最大的坑不是模型，而是"喂给模型的语料自己就脏"。
2. **document identity**：用原始字节哈希而非解析文本哈希，保证身份与解析器无关、上传即定、稳定幂等。
3. **point identity**：Qdrant 向量点用 `documentId:chunkId:indexVersion` → UUID v3 确定性 ID，保证 upsert 幂等与精确清理——文档身份和向量点身份是两套体系，必须分开。
4. **data governance**：canonical 选择先看向量完备性再比状态再 tie-break；soft-delete 用 self-id 而非统一 `is_deleted=1`，既保留 lineage 又靠 `UNIQUE(content_sha256,is_deleted)` 锁住活跃唯一性。
5. **active-only Search**：检索结果里任何 unknown/inactive/deleted 文档 fail closed，配合批量 active 校验，避免把脏/失效文档喂给 LLM，也避免 N+1。
6. **migration order**：M1 additive → 兼容代码 → Backfill 原始哈希 → PR-3 去重 → M2 加唯一约束，每步可验证可回退；Qdrant 清理采用 exact Point ID + snapshot-before-delete 的**受控恢复设计**（PR-3 数据后态已验证，但部分 snapshot / runner raw evidence 未完整保存，故不声称已完全证明可回滚 / 零误伤）。
7. **REAL benchmark**：真实接入智谱 embedding-3 + glm-4.5-air 跑通 17-case 评估，去重后检索质量提升；但我们**诚实标注** latency 本轮 regressed 且不能归因于去重，也不称 production accuracy。
8. **evidence / engineering safety**：REAL Ask 正常会写入 append-only `ai_call_log`；上一评估 Gate 因一律禁止 DB write，**实际执行确实违反了该 Gate 的程序约束**（产生 18 条审计写入，`PREVIOUS_REAL_EVAL_PROCEDURAL_RESULT=FAILED_CONSTRAINT`），但后续 Closure 通过日志+数据库双侧交叉对账确认写入只限审计日志、未发现 corpus / Qdrant mutation。这促使我们把未来 Gate 从"数据库零写"改为"允许预期 audit writes 但严格验证 corpus invariants"——这体现的工程纪律比模型本身更值钱。
