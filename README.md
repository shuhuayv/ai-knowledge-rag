# ai-knowledge-rag

企业级 AI 知识库问答系统 —— 基于 **Spring Boot + RAG（检索增强生成）**。检索层使用真实智谱 `embedding-3`（1024 维）向量 + Qdrant；生成层 Chat 使用 OpenAI-compatible 接口（默认智谱 `glm-4.5-air`）。

> 说明：本项目**未使用 Spring AI**，Chat / Embedding 调用均通过 **Spring RestClient** 直接对接 OpenAI-compatible 协议，可平滑切换到阿里百炼、DeepSeek、火山方舟等兼容 provider。

## 真实能力边界（诚实口径）

- **检索层 = 真实语义检索**：真实 `embedding-3` 1024 维向量，写入独立 Qdrant Collection `kb_chunks_zhipu_embedding_3_1024_v1`，与 Mock（384 维 `kb_chunks`）物理隔离。
- **生成层 Chat = 真实智谱**（本地 Demo 经 Keychain 注入 Key，默认 `glm-4.5-air`、`thinking` 默认关闭、超时 90s、对 429/1302/1305 自动退避重试）。
- **不是** 训练大模型、不是自研向量库、不做线上高并发承诺；本地 Demo 无登录 / 权限。
- 真实 Key 的端到端冒烟需本机注入 Key 后运行，README 只描述已落地的代码与构建/测试能力。

## 技术栈

- Java 21 + Spring Boot 4.1
- Maven 3.9
- Spring RestClient（OpenAI-compatible Chat / Embedding 调用，非 Spring AI）
- MySQL（业务元数据） + Redis（缓存） + Qdrant（向量库）
- MyBatis-Plus + Lombok + Jakarta Validation
- Springdoc OpenAPI / Swagger UI
- PDFBox（PDF 解析）

## 系统架构

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

## 核心业务流程

1. 文档上传（TXT/PDF）→ 解析 → Chunk 切分
2. 向量化（Mock 或真实 Embedding）→ 写入 Qdrant
3. 语义检索：`POST /api/search` 返回 TopK
4. RAG 问答：`POST /api/rag/ask` → 检索 + Chat 生成 + 引用来源（references 含 documentId / chunkId / score）

## 本地依赖

- JDK 21+、Maven 3.9+
- Docker：mysql8（3307→3306）、redis7（6379）、qdrant（6333）
- 真实模式需本机 Keychain 中 `ai_dev`（DB）与 `ai-knowledge-rag-zhipu`（智谱 Key）

## 环境变量

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
| `DB_HOST/DB_PORT/DB_NAME/DB_USERNAME/DB_PASSWORD` | 127.0.0.1/3307/ai_knowledge_rag/ai_dev | 数据库 |

## 本地启动

### 1. 配置（从 Keychain 读取，无需手写明文）

```bash
# 复制示例并查看（不要填真实密码）
cp .env.example .env
# 真实启动推荐用仓库脚本（自动从 Keychain 读密）：
bash scripts/start_rag_local.sh      # 前台日志在 .demo-run/logs
bash scripts/stop_rag_local.sh
```

### 2. Mock 模式（默认，无需 Key）

```bash
mvn spring-boot:run
```

### 3. 真实 Chat（glm-4.5-air）

```bash
export AI_MOCK_ENABLED=false AI_PROVIDER=zhipu
export ZHIPU_API_KEY='<从 Keychain 读取，绝不提交>'
export AI_MODEL='glm-4.5-air' AI_THINKING_TYPE=disabled AI_TIMEOUT_SECONDS=90
mvn spring-boot:run
```

### 4. 真实 Embedding（embedding-3, 1024 维）

```bash
export AI_EMBEDDING_PROVIDER=zhipu AI_EMBEDDING_MODEL=embedding-3 AI_EMBEDDING_DIMENSIONS=1024
export AI_EMBEDDING_FALLBACK_ENABLED=false ZHIPU_API_KEY='<从 Keychain 读取>'
mvn spring-boot:run
```

## 测试命令

```bash
mvn -B test          # 当前 68 个单元测试通过（MockWebServer / Qdrant 本地实例），BUILD SUCCESS
mvn -B package -DskipTests
```

测试覆盖：Embedding 双模式、CollectionNameResolver、Qdrant 向量读写、检索、RAG 问答、Embedding 状态接口、限流退避（见 `src/test`）。

## CI

GitHub Actions：`.github/workflows/ci.yml`（push/PR main，Java 21，Maven 缓存，`mvn -B test` + `mvn -B package -DskipTests`）。测试配置不读取生产秘密，不调用真实 API。

## API / 页面入口

| 入口 | 地址 |
|------|------|
| Swagger UI | http://localhost:8080/swagger-ui.html |
| OpenAPI JSON | http://localhost:8080/v3/api-docs |
| 文档接口 | `/api/documents/*` |
| 检索 | `POST /api/search` |
| RAG 问答 | `POST /api/rag/ask` |
| Embedding 状态 | `GET /api/embedding/status` |

详见 [docs/rag.md](docs/rag.md)、[docs/search.md](docs/search.md)、[docs/REAL_EMBEDDING.md](docs/REAL_EMBEDDING.md)。

## 演示步骤

```bash
bash scripts/reset_demo_data.sh --yes
bash scripts/demo_real_embedding_flow.sh     # upload→parse→index→search→ask，打印 references 与 score
```

样例文档：`samples/company_policy.txt`。

## 已知限制

- 多轮会话历史暂未支持。
- 真实 Chat 受智谱速率限制，已做退避重试但仍可能短时不可用。
- 评估指标见下方「检索评估」，生成层指标以本机真实运行结果为准。

## 面试亮点

- Mock / Real Embedding 双模式 + Collection 物理隔离，维度安全。
- Chat 限流退避（429/1302/1305）+ 并发 Semaphore + thinking 关闭，稳定输出。
- 检索透明性：candidate/returned 计数、Embedding 元数据、无结果固定文案、状态接口不泄露 Key。
- 68 个单测 + CI 绿。

## 故障排查

- 启动报 `Embedding` 维度冲突：确认真实模式 Collection `kb_chunks_zhipu_embedding_3_1024_v1` 维度为 1024；`bash scripts/check_embedding_config.sh` 预检。
- Chat 空回答：旧 GLM 默认开启思考会耗尽 max_tokens；本项目 `AI_THINKING_TYPE=disabled` 已规避。
- 端口占用：RAG 用 8080；确认 mysql8(3307)、redis7(6379)、qdrant(6333) 已启动。

## 检索评估（运行方式 + 指标）

运行方式（真实模式，指标待第四天真实运行后回填）：

```bash
# 需先完成文档解析与向量化索引（真实 embedding-3）
export AI_EMBEDDING_PROVIDER=zhipu ZHIPU_API_KEY='<Keychain>'
bash scripts/evaluate_real_embedding.sh     # 计算 hit@1/hit@3/hit@5/MRR/无结果率/延迟
```

指标（**第四天真实运行后回填，以下为占位**）：

| 数据集 | 样本数 | Hit@1 | Hit@3 | Hit@5 | MRR | 无结果率 | P95 延迟 |
|--------|--------|-------|-------|-------|-----|----------|----------|
| 待建（10–20 条，对应真实已索引文档） | TBD | TBD | TBD | TBD | TBD | TBD | TBD |

详见 [docs/retrieval-evaluation.md](docs/retrieval-evaluation.md)。

## 文档索引

[docs/configuration.md](docs/configuration.md) · [docs/runbook.md](docs/runbook.md) · [docs/demo-script.md](docs/demo-script.md) · [docs/database.md](docs/database.md) · [docs/parser.md](docs/parser.md) · [docs/vector-index.md](docs/vector-index.md) · [docs/search.md](docs/search.md) · [docs/rag.md](docs/rag.md) · [docs/qdrant.md](docs/qdrant.md) · [docs/REAL_EMBEDDING.md](docs/REAL_EMBEDDING.md) · [docs/retrieval-evaluation.md](docs/retrieval-evaluation.md) · [docs/roadmap.md](docs/roadmap.md)
