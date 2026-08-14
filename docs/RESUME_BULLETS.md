# Resume Bullet Candidates — ai-knowledge-rag

> 三个版本，每版 2–3 条。所有数字为当前真实数字；**未虚构**高并发 QPS / 生产用户数 / 线上准确率 / SLA / 百万文档 / 微服务集群 / Kubernetes。

---

## 版本 A — 偏 Java 后端

- 基于 Spring Boot 4.1 + MyBatis-Plus 构建 RAG 知识库后端，设计 document-level 身份（`content_sha256 = SHA-256(raw bytes)`）与幂等上传，重复上传直接返回已有文档而非新建。
- 实现 historical duplicate 治理：canonical 选择规则 `VECTOR_COMPLETENESS → STATUS_RANK → CREATED_AT → ID`，soft-delete 自引用 + `UNIQUE(content_sha256, is_deleted)` 唯一约束，子行保留审计。
- 设计分闸数据库迁移 M1（additive）→ 兼容代码 → Backfill → PR-3 去重 → M2 约束，每步可独立验证；配套 **202 个 Maven 单元测试，0 failures**。

## 版本 B — 偏 AI 应用 / RAG

- 搭建 Spring Boot + RAG 问答系统，真实接入智谱 `embedding-3`（1024 维）与 `glm-4.5-air`，检索（Qdrant）与生成（OpenAI-compatible Chat）经 RestClient 直连，支持 Mock/Real 双模式与 Collection 物理隔离。
- 在受控 17-case 回归基准上，去重后检索质量提升：Hit@3 0.5→1.0、MRR 0.6→0.75、faithfulness 0.875→0.9375；诚实标注 latency 本轮更高，受运行环境与 API 波动影响，当前证据不足以将该变化直接归因于去重。
- Search 对所有未知/失效/已删文档 id fail closed，采用 bounded overfetch + batched active lookup，避免 N+1 与 stale point 占满 topK。

## 版本 C — 偏工程治理 / 数据一致性

- 主导 RAG 语料数据治理：定义两套身份体系（文档级 `content_sha256` 与向量点级确定性 UUID v3），精确治理历史重复文档并保留 lineage 供审计。
- 建立 Gate-based 迁移与证据验证机制：dry-run、snapshot-before-delete、source fingerprint、exact Point-ID cleanup，体现受控恢复设计以降低误删风险（PR-3 数据后态已验证，但部分 snapshot / runner raw evidence 未完整保存，故不声称已完全证明可回滚 / 零误伤）。
- 通过日志+数据库双侧交叉对账确认：REAL Ask 正常会写入 append-only `ai_call_log`；上一评估 Gate 因一律禁止 DB write 而**确实被违反**（产生 18 条审计写入，非语料变更，`PREVIOUS_REAL_EVAL_PROCEDURAL_RESULT=FAILED_CONSTRAINT`），后续据此把未来 Gate 从"数据库零写"改为"允许预期审计写入并严格校验 corpus invariants"。
