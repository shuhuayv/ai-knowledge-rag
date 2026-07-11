# 真实智谱 Embedding 接入与检索透明性增强

> 本文档对应任务：双模式 mock / zhipu embedding-3 接入、384/1024 Collection 隔离、检索结果透明性增强。
> 安全边界：API Key 仅来自环境变量，状态接口只返回 `apiKeyConfigured: true/false`，绝不返回 Key 本身。

---

## 1. 背景

原系统仅使用 Mock Embedding（SHA-256 伪向量，384 维），TopK 检索在工程上闭环，但**向量无真实语义**，
检索结果不可用于质量评估或生产问答。为获得真实语义检索，需接入真实 Embedding API（智谱 `embedding-3`，1024 维）。

## 2. Mock 风险

- Mock 向量由文本 SHA-256 哈希生成，无真实语义；任意两段不同文本得到的是互不相关的伪向量，相似度无语义意义，不能用于质量评估；
- 不同文档的无关片段可能因字符重叠被误判为"相关"，导致 RAG 引用错误片段；
- 无法衡量真实检索质量（hit@1/hit@3/MRR 无意义）；
- 给面试/演示造成"系统能工作"的误导。

## 3. 双模式架构

通过 `ai.embedding.provider` 切换，由 `EmbeddingConfiguration` 装配**恰好一个** `EmbeddingService` Bean：

| provider | 实现类 | 维度 | 网络 | Key | Collection |
|----------|--------|------|------|-----|-----------|
| `mock`（默认） | `MockEmbeddingServiceImpl` | 384 | 无 | 无 | `kb_chunks`（legacy） |
| `zhipu` | `ZhipuEmbeddingServiceImpl` | 1024 | 智谱 API | 需要 | `kb_chunks_zhipu_embedding_3_1024_v1` |

`MockEmbeddingServiceImpl` 与 `ZhipuEmbeddingServiceImpl` **不使用 `@Service`**，由 `@Configuration` 的 `@Bean`
（`@ConditionalOnProperty`）二选一装配，避免 Spring 上下文歧义。

## 4. Chat / Embedding 解耦

Chat（`OpenAiCompatibleChatModelServiceImpl` / `MockChatModelServiceImpl`）与 Embedding（`EmbeddingService`）
完全解耦：
- Chat 由 `ai.mock-enabled` 控制（`true`=Mock，`false`=真实 GLM）；
- Embedding 由 `ai.embedding.provider` 控制（`mock`/`zhipu`）；
- 两者可任意组合（如 Mock Chat + 真实 Embedding，便于在无 Chat Key 时评估检索质量）。

## 5. EmbeddingService 设计

接口新增方法，统一两种实现：

```java
List<Float> embed(String text);
List<List<Float>> embedBatch(List<String> texts);
String provider();          // "mock" / "zhipu"
String model();             // "mock" / "embedding-3"
int dimensions();           // 384 / 1024
EmbeddingMode mode();       // MOCK / REAL
boolean real();             // false / true
```

`ZhipuEmbeddingServiceImpl` 构造器接收**已构建好的 `RestClient` + 配置值**，便于单测注入 MockWebServer 的 RestClient，
从而隔离真实网络与真实 Key。

## 6. 智谱 API 请求

- 端点：`POST {endpoint}`，`endpoint` 默认 `https://open.bigmodel.cn/api/paas/v4/embeddings`；
- 运行时去掉尾部 `/embeddings` 作为 RestClient `baseUrl`；
- 请求体：`{"model":"embedding-3","input":[...],"dimensions":1024}`（embedding-3 不传 dimensions 时默认返回 2048 维，必须显式传 1024）；
- 鉴权：`Authorization: Bearer <API_KEY>`，Key 仅来自 `${ZHIPU_API_KEY}` / `${AI_API_KEY}` 注入到 RestClient 头；
- 响应：`{"data":[{"index":0,"embedding":[...]},...],"model":"embedding-3"}`。

## 7. 批量与重试

- `embedBatch` 按 `batch-size`（默认 16）分批，单批上限 64；
- 对 `429` / `5xx` 指数退避重试，默认 `max-retries=2`，退避 `500ms * 2^attempt`（`Thread.sleep`）；
- `400` / `401` / `403` **不重试**直接抛异常；
- 超过 `max-retries` 后整体失败；
- 响应校验：data 数量、index 范围/唯一性、向量维度、NaN/Inf 全部校验通过才返回，任一失败整批抛异常（不写 Qdrant）。

## 8. 384 / 1024 隔离

- Mock（384）与 Real（1024）使用**不同 Qdrant Collection**，物理隔离，维度不会混用；
- `ensureCollection` 对已存在 Collection 读取远端 `size`/`distance`，**不一致明确抛异常**，绝不自动删除/重建；
- 旧 Mock 数据（`kb_chunks`，384 维）完全保留，不受影响。

## 9. Collection 命名

唯一来源：`CollectionNameResolver`。
- Mock 模式返回 legacy 名 `${app.qdrant.collection:kb_chunks}`；
- Real 模式返回 `kb_chunks_{provider}_{model}_{dimensions}_{version}`
  （如 `kb_chunks_zhipu_embedding_3_1024_v1`）；
- 命名规则：小写、非字母数字转 `_`、合并连续 `_`，天然实现 384/1024 隔离。

## 10. 索引元数据

索引时写入透明性元数据：
- `kb_vector_record`：`embedding_provider` / `embedding_model` / `embedding_dimensions` / `index_version`；
- `kb_document`：`embedding_provider` / `embedding_model` / `embedding_dimensions` / `vector_collection` /
  `index_version` / `indexed_at`；
- pointId 确定性：`{documentId}_{chunkId}_{indexVersion}`（如 `1_10_v1`），重索引幂等（覆盖同一 Point）。

## 11. SQL Migration

`sql/migrations/20260710_add_real_embedding_metadata.sql`：
- 增量 `ALTER TABLE`，为 `kb_document` / `kb_vector_record` / `ai_call_log` 增加可空列；
- 不 DROP、不删数据，保留旧 Mock 数据；
- 执行前应先用 INFORMATION_SCHEMA 预检列是否存在；若列已存在，对应 ALTER 会报 "Duplicate column"，需跳过该条 ALTER（不要整脚本忽略）；本脚本保持不 DROP/不删数据。

## 12. 重新索引

切换 provider 后，需对文档**重新执行索引**（`POST /api/documents/{id}/index`）：
- Real 模式写入独立 Collection，不与 Mock 数据混用；
- 确定性 pointId 保证同一文档重索引幂等；
- 检索前确认 `kb_document.vector_collection` 与目标 Collection 一致。

## 13. 搜索诊断字段

`SearchResponse` 新增：
- `retrievalCandidateCount`：min-score 过滤前候选数；
- `retrievalReturnedCount`：过滤后返回数（仅 `score >= rag.retrieval.min-score`）。

`RagAskResponse` 新增 11 个字段：`embeddingProvider` / `embeddingModel` / `embeddingDimensions` /
`embeddingMode` / `collectionName` / `retrievalTopK` / `retrievalMinScore` /
`retrievalCandidateCount` / `retrievalReturnedCount` / `fallbackUsed` / `retrievalQualityNote`。

## 14. 无结果处理

- 经 min-score 过滤后仍无有效上下文时，**不调用 Chat**，直接返回固定文案：
  `当前知识库中未检索到足够相关的内容，无法基于已上传文档可靠回答该问题。`；
- `promptPreview` 标记为 `[无有效上下文]`，避免大模型凭空编造；
- `retrievalQualityNote` 标明"无有效上下文"或"候选低于阈值被过滤"。

## 15. 状态接口

`GET /api/embedding/status` 返回：

```json
{
  "provider": "zhipu",
  "model": "embedding-3",
  "dimensions": 1024,
  "mode": "REAL",
  "collectionName": "kb_chunks_zhipu_embedding_3_1024_v1",
  "fallbackEnabled": false,
  "apiKeyConfigured": true
}
```

`apiKeyConfigured` 仅根据 `${ZHIPU_API_KEY}` / `${AI_API_KEY}` 环境变量是否存在判断，**绝不返回 Key 本身/长度/前缀/后缀**。

## 16. Mock 模式启动（默认）

```bash
# 无需 Key
mvn spring-boot:run
```

## 17. 真实模式启动

```bash
export AI_EMBEDDING_PROVIDER=zhipu
export ZHIPU_API_KEY='<your-local-api-key>'     # 仅本地，绝不提交
# 可选：AI_EMBEDDING_MODEL / AI_EMBEDDING_DIMENSIONS / AI_EMBEDDING_BATCH_SIZE 等
mvn spring-boot:run
```

`fallback-enabled` 默认 `false`：缺 Key 时明确失败，不静默降级到 Mock。

## 18. 测试方式

纯单元测试（JUnit 5 + Mockito + AssertJ + okhttp3 MockWebServer），**不使用 `@SpringBootTest`**，
不触碰真实 API / Key / Qdrant / MySQL / Redis：

```bash
mvn -o clean test
```

覆盖：Mock 稳定性、Zhipu 端点/模型/维度/分批/顺序恢复/各类校验失败/重试/缺 Key、Collection 命名与隔离、
`ensureCollection` 维度校验、`getVectorSize`、索引元数据与幂等、min-score 过滤、无结果不调用 Chat、状态接口不泄露 Key。

> 诚实声明：真实 API 的冒烟测试（使用真实 Key 调用智谱 embedding-3）**尚未由 Agent 执行**。目前仅保证
> `mvn clean test` 构建通过 + 纯单元测试（okhttp3 MockWebServer 模拟真实 API）全部通过；真实端到端链路
> （上传→解析→真实向量化→检索）需用户在本地配置 `ZHIPU_API_KEY` / `AI_API_KEY` 后手动验证。

## 19. 评估方法

使用 `scripts/evaluate_real_embedding.sh`：准备 3 主题 demo 文档，对 4 个问题（3 主题相关 + 1 无关"红烧肉"）
做 TopK=3 检索，计算 hit@1 / hit@3 / MRR / 无关问题最高分。**指标均由真实运行计算，绝不写死**。
详见 `docs/retrieval-evaluation.md`。

## 20. 常见错误

- `ai.embedding.provider=zhipu` 但缺 Key → 启动失败（IllegalArgumentException），符合预期；
- Collection 维度不一致 → `ensureCollection` 抛 `IllegalStateException`（需手动重建 Collection）；
- 检索为空 → 检查文档是否用同一 provider 索引、Collection 是否匹配、min-score 是否过高；
- 单元测试连真实网络 → 说明误用了 `@SpringBootTest`，应改为纯单测。

## 21. 安全边界

- **API Key 仅来自环境变量** `${ZHIPU_API_KEY}` / `${AI_API_KEY}`，绝不硬编码、绝不打印、绝不入库；
- 状态接口只返回 `apiKeyConfigured: true/false`；
- `default fallback-enabled=false`：缺 Key 明确失败，不静默降级；
- 无 rerank、无多轮、非生产 SaaS、无大规模评测；
- score 是**相似度**非答案正确率；真实 Chat 仍可能有模型误差；
- PDF 仅可提取文本，无 OCR（除非文档本身含可提取文本层）。

## 22. 面试口径

> "系统支持 Mock 与真实双模式 Embedding。真实模式接入智谱 `embedding-3`（1024 维），
> 通过 `EmbeddingService` 抽象与条件化 Bean 装配实现零侵入切换；384 与 1024 用独立 Qdrant Collection 物理隔离，
> 索引写入 provider/model/dimensions/version 等透明性元数据，检索与 RAG 响应暴露 candidate/returned 计数与质量说明，
> 无结果时不调用大模型而是返回固定引导文案。API Key 仅来自环境变量，状态接口只暴露 `apiKeyConfigured` 布尔值，
> 绝不泄露 Key。所有逻辑均有纯单元测试覆盖（MockWebServer 模拟真实 API）。"
