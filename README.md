# ai-knowledge-rag

本地 / 受控开发项目：基于 **Spring Boot + RAG（检索增强生成）** 的 AI 知识库问答系统。检索层使用真实智谱 `embedding-3`（1024 维）向量 + Qdrant；生成层 Chat 使用 OpenAI-compatible 接口（默认智谱 `glm-4.5-air`）。

> 说明：本项目**未使用 Spring AI**，Chat / Embedding 调用均通过 **Spring RestClient** 直接对接 OpenAI-compatible 协议，可平滑切换到阿里百炼、DeepSeek、火山方舟等兼容 provider。

## 1. 真实能力边界（诚实口径）

- **检索层 = 真实语义检索**：真实 `embedding-3` 1024 维向量，写入独立 Qdrant Collection `kb_chunks_zhipu_embedding_3_1024_v1`，与 Mock（384 维 `kb_chunks`）物理隔离。
- **生成层 Chat = 真实智谱**（本机经 Keychain 注入 Key，默认 `glm-4.5-air`、`thinking` 默认关闭、超时 90s、对 429/1302/1305 自动退避重试）。
- **不是** 训练大模型、不是自研向量库、不做线上高并发承诺；本地 Demo 无登录 / 权限。
- 真实 Key 的端到端冒烟需本机注入 Key 后运行，README 只描述已落地的代码与构建/测试能力。

## 2. 技术栈

- Java 21 + Spring Boot 4.1
- Maven 3.9
- Spring RestClient（OpenAI-compatible Chat / Embedding 调用，非 Spring AI）
- MySQL（业务元数据） + Redis（缓存） + Qdrant（向量库）
- MyBatis-Plus + Lombok + Jakarta Validation
- Springdoc OpenAPI / Swagger UI
- PDFBox（PDF 解析）

## 3. 系统架构

```
上传 → 解析(PDFBox) → Chunk(滑动窗口) → 向量化(EmbeddingService: Mock/Real)
                                                            ↓
                                                       Qdrant(Collection 隔离)
                                                            ↑
问答: 检索(SearchService, TopK 语义) → 拼 Prompt → Chat(ChatModelService, 限流退避) → 引用来源
```

- `EmbeddingService`：抽象 + `MockEmbeddingServiceImpl`（SHA-256 伪向量 384 维）/ `ZhipuEmbeddingServiceImpl`（真实 embedding-3 1024 维，批量 + 指数退避重试）。
- `CollectionNameResolver`：按 Mock/Real 解析不同 Collection 名，避免维度冲突。
- `ChatModelService`：`OpenAiCompatibleChatModelServiceImpl`（Semaphore 并发=1 + 退避重试 + thinking 开关）。
- `EmbeddingStatusController`：仅返回 `apiKeyConfigured` 布尔，绝不泄露 Key。

## 4. 核心业务流程

1. 文档上传（TXT/PDF）→ 解析 → Chunk 切分
2. 向量化（Mock 或真实 Embedding）→ 写入 Qdrant
3. 语义检索：`POST /api/search` 返回 TopK
4. RAG 问答：`POST /api/rag/ask` → 检索 + Chat 生成 + 引用来源（references 含 documentId / chunkId / score）

## 5. 两级身份设计（Two-level Identity）

**文档级身份 — `content_sha256`**

- 定义：`SHA-256(raw uploaded file bytes)`（原始上传字节的哈希）。
- **严禁**把它写成 normalized text hash / PDF 提取文本 hash / chunk text hash / filename+content hash / path+content hash。
- 重复上传查重：`content_sha256 + is_deleted=0`，用于阻止新的 active duplicate document。

**向量点身份 — Qdrant 确定性 point ID**

- 来源：`documentId:chunkId:indexVersion` → 确定性 UUID v3。
- 作用：保证 vector point upsert 的 identity / 幂等性。
- 重要：`content_sha256` 与 Qdrant point ID 是**两套不同**的身份体系，README / 面试稿必须明确区分。

## 6. 幂等上传（Idempotent Upload）

重复上传判定使用 `content_sha256 + is_deleted=0`：若已存在 preferred active document，则直接返回该文档，**不新增** duplicate document。

## 7. Historical Dedup 治理

- 规范规则（canonical rule）：`VECTOR_COMPLETENESS → STATUS_RANK → CREATED_AT ASC → ID ASC`。
- 历史重复行采用 **soft-delete（自引用自身 document id）**，而非物理删除；canonical 行 `canonical_document_id=NULL`、`dedup_batch=NULL`、`is_deleted=0`。
- 子行（child rows）保留用于审计；`kb_chunk` / `kb_vector_record` 保留 for audit，正常读取/解析/索引走 active-only。
- 迁移顺序：`M1（additive migration）→ compatible code → Backfill → PR-3（historical dedup）→ M2（unique constraint）`。

## 8. Search / RAG 安全

- Qdrant 返回结果中：`documentId=null` / unknown document / inactive document / deleted document **全部 fail closed**。
- 采用 **bounded overfetch + batched active-document lookup**，避免 N+1 以及 stale point 占满 topK。
- RAG 引用（references）仅指向 active document。

## 9. Qdrant 安全

- REAL / Mock Collection **物理隔离**（不同 Collection、不同维度）。
- 确定性 point ID 保证 upsert 幂等。
- Historical cleanup 仅 retired current REAL managed collection 的 exact Point IDs，**禁止** wildcard / broad payload delete / collection delete；cleanup 前做 snapshot-before-delete 安全副本。
- legacy Mock Collection（5 points，384 维）属于 **SEPARATE_TECH_DEBT**，尚未清理，README 如实说明 Mock / REAL 空间物理分离，**不得写** legacy Mock 已清理。

## 10. 数据库迁移策略

- `M1`：additive migration（兼容代码先行）。
- 兼容代码 + raw hash backfill + historical dedup + `M2` unique constraint。
- `M2` 唯一约束：`UNIQUE(content_sha256, is_deleted)`。

## 11. Mock / REAL 运行时

- checked-in default 可保留 Mock；Current REAL runtime 已受控验证：Zhipu `embedding-3`（1024 维）+ `glm-4.5-air`。
- 真实 Key 经本机 Keychain 注入，README / 代码均不暴露 Key。

## 12. 评估（Evaluation）

小型受控 17-case 回归基准（语料含重复演示文档，不代表生产准确率）：

| 指标 | Historical Verified | Current Post-dedup |
|------|---------------------|--------------------|
| Hit@1 | 0.5 | 0.5 |
| Hit@3 | 0.5 | 1.0 |
| Hit@5 | 1.0 | 1.0 |
| MRR | 0.6 | 0.75 |
| No-answer rejection | 1.0 | 1.0 |
| Faithfulness heuristic | 0.875 | 0.9375 |

- 检索排序质量在该小型受控基准上改善（Hit@3 0.5→1.0、MRR 0.6→0.75），结果**一致于**历史重复污染减少；Hit@1 / Hit@5 / 无答案拒答率不变。
- **不可**称 RAG accuracy = 100% / production accuracy = 100% / 准确率提高 100% / PR-3 必然导致模型准确率提高 / production performance improved。
- 诚实说明：**当前运行的 latency 更高**（检索 avg 1570.13 / P50 1337.5 / P95 2492.78 ms；生成 avg 3267.92 / P50 2953.7 / P95 5049.7 ms，对比历史检索 avg 1044.82 / P50 1056.0 / P95 1463.06 ms、生成 avg 2596.51 / P50 2510.6 / P95 3799.0 ms）。latency 变化**不应直接归因于 dedup**。

## 13. 验证（Validation）

- **202 Maven tests**，0 failures / 0 errors / 0 skipped。
- `mvn -B package -DskipTests` → BUILD SUCCESS。
- PR #6（`feat(rag): add document identity, dedup governance, and soft-delete safety`）已 MERGED 至 main。
- REAL runtime 受控验证完成；same-17 受控评估完成。

## 14. 安全 / 工程经验（Safety / Engineering Lessons）

- Gate-based migration（分闸迁移）：dry-run、snapshot、source fingerprint、exact cleanup、evidence verification。
- 迁移顺序与兼容代码先行，避免一次性大改。
- 评估结论须区分 controlled benchmark 与 production measurement，不夸大。

> 注：README 不展开内部评估事件细节；完整的 caveats（PR-3 / M2 / REAL-eval `ai_call_log` side effect / legacy Mock 技术债）见 `docs/FINAL_ENGINEERING_FACTS.md`。

## 15. 已知边界 / 未来工作（Known Boundaries / Future Work）

- legacy Mock Collection retirement（当前为独立技术债）。
- 可能的 reranking / observability / 更大评估语料。
- 明确：**以上均非当前实习 Demo 所必需**；本项目为 local / controlled development project，未验证 high concurrency / multi-tenant / large-scale traffic / production SLA / real-world accuracy。

## 16. 本地依赖与启动

### 本地依赖

- JDK 21+、Maven 3.9+
- Docker：mysql8（3307→3306）、redis7（6379）、qdrant（6333）
- 真实模式需本机 Keychain 中 `ai_dev`（DB）与 `ai-knowledge-rag-zhipu`（智谱 Key）

### 环境变量（节选）

| 变量 | 默认 | 说明 |
|------|------|------|
| `AI_PROVIDER` | `mock` | chat provider（真实用 `zhipu`） |
| `AI_MODEL` | `glm-4.5-air` | chat 模型 |
| `AI_API_KEY` / `ZHIPU_API_KEY` | 空 | 智谱 Key（不提交） |
| `AI_THINKING_TYPE` | `disabled` | 关闭 GLM 深度思考，避免耗尽 max_tokens |
| `AI_TIMEOUT_SECONDS` | `90` | chat 超时 |
| `AI_CHAT_MAX_RETRIES` | `3` | 限流退避重试次数 |
| `AI_EMBEDDING_PROVIDER` | `mock` | embedding provider（真实用 `zhipu`） |
| `AI_EMBEDDING_MODEL` | `embedding-3` | 真实 embedding 模型 |
| `AI_EMBEDDING_DIMENSIONS` | `1024` | 维度 |
| `AI_EMBEDDING_FALLBACK_ENABLED` | `false` | 缺 Key 时明确失败，不静默降级 |
| `DB_HOST/DB_PORT/DB_NAME/DB_USERNAME/DB_PASSWORD` | localhost/3307/ai_knowledge_rag/ai_dev | 数据库 |

### 启动

```bash
# 1. 配置（从 Keychain 读取，无需手写明文）
cp .env.example .env
bash scripts/start_rag_local.sh      # 前台日志在 .demo-run/logs
bash scripts/stop_rag_local.sh

# 2. Mock 模式（默认，无需 Key）
mvn spring-boot:run

# 3. 真实 Chat（glm-4.5-air）
export AI_MOCK_ENABLED=false AI_PROVIDER=zhipu
export ZHIPU_API_KEY='<从 Keychain 读取，绝不提交>'
export AI_MODEL='glm-4.5-air' AI_THINKING_TYPE=disabled AI_TIMEOUT_SECONDS=90
mvn spring-boot:run

# 4. 真实 Embedding（embedding-3, 1024 维）
export AI_EMBEDDING_PROVIDER=zhipu AI_EMBEDDING_MODEL=embedding-3 AI_EMBEDDING_DIMENSIONS=1024
export AI_EMBEDDING_FALLBACK_ENABLED=false ZHIPU_API_KEY='<从 Keychain 读取>'
mvn spring-boot:run
```

## 17. 测试与 CI

```bash
mvn -B test                      # 当前 202 个单元测试（MockWebServer / Qdrant 本地实例），BUILD SUCCESS
mvn -B package -DskipTests
```

测试覆盖：Embedding 双模式、CollectionNameResolver、Qdrant 向量读写、检索、RAG 问答、Embedding 状态接口、限流退避（见 `src/test`）。

CI：GitHub Actions `.github/workflows/ci.yml`（push / PR 至 main，Java 21，Maven 缓存，`mvn -B test` + `mvn -B package -DskipTests`）。测试配置不读取生产秘密，不调用真实 API。

## 18. API / 页面入口

| 入口 | 地址 |
|------|------|
| Swagger UI | http://localhost:8080/swagger-ui.html |
| OpenAPI JSON | http://localhost:8080/v3/api-docs |
| 文档接口 | `/api/documents/*` |
| 检索 | `POST /api/search` |
| RAG 问答 | `POST /api/rag/ask` |
| Embedding 状态 | `GET /api/embedding/status` |

详见 [docs/rag.md](docs/rag.md)、[docs/search.md](docs/search.md)、[docs/REAL_EMBEDDING.md](docs/REAL_EMBEDDING.md)。

## 19. 演示步骤

```bash
bash scripts/reset_demo_data.sh --yes
bash scripts/demo_real_embedding_flow.sh     # upload→parse→index→search→ask，打印 references 与 score
```

样例文档：`samples/company_policy.txt`。

## 20. 故障排查

- 启动报 `Embedding` 维度冲突：确认真实模式 Collection `kb_chunks_zhipu_embedding_3_1024_v1` 维度为 1024；`bash scripts/check_embedding_config.sh` 预检。
- Chat 空回答：旧 GLM 默认开启思考会耗尽 max_tokens；本项目 `AI_THINKING_TYPE=disabled` 已规避。
- 端口占用：RAG 用 8080；确认 mysql8(3307)、redis7(6379)、qdrant(6333) 已启动。

## 21. 文档索引

[docs/configuration.md](docs/configuration.md) · [docs/runbook.md](docs/runbook.md) · [docs/demo-script.md](docs/demo-script.md) · [docs/database.md](docs/database.md) · [docs/parser.md](docs/parser.md) · [docs/vector-index.md](docs/vector-index.md) · [docs/search.md](docs/search.md) · [docs/rag.md](docs/rag.md) · [docs/qdrant.md](docs/qdrant.md) · [docs/REAL_EMBEDDING.md](docs/REAL_EMBEDDING.md) · [docs/retrieval-evaluation.md](docs/retrieval-evaluation.md) · [docs/roadmap.md](docs/roadmap.md)
