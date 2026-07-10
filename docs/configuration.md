# 环境变量配置

所有配置通过环境变量读取，`application.yml` 中设置了默认值。

## 数据库

| 环境变量 | 默认值 | 说明 |
|------|------|------|
| DB_NAME | ai_knowledge_rag | 数据库名称 |
| DB_USERNAME | ai_dev | 数据库用户名 |
| DB_PASSWORD | (空) | 数据库密码（必填） |

## Redis

| 环境变量 | 默认值 | 说明 |
|------|------|------|
| REDIS_HOST | localhost | Redis 主机 |
| REDIS_PORT | 6379 | Redis 端口 |

## Qdrant 向量数据库

| 环境变量 | 默认值 | 说明 |
|------|------|------|
| QDRANT_URL | http://localhost:6333 | Qdrant HTTP API 地址（优先级高于 host+port） |
| QDRANT_HOST | localhost | Qdrant 主机（QDRANT_URL 为空时使用） |
| QDRANT_PORT | 6333 | Qdrant 端口（QDRANT_URL 为空时使用） |
| QDRANT_COLLECTION | kb_chunks | Qdrant Collection 名称 |

## 文件上传

| 环境变量 | 默认值 | 说明 |
|------|------|------|
| UPLOAD_DIR | uploads | 文件上传目录（建议使用绝对路径，如 `/data/ai-knowledge-rag/uploads`） |

## Chunk 切分

| 环境变量 | 默认值 | 说明 |
|------|------|------|
| CHUNK_SIZE | 500 | 每个 Chunk 最大字符数 |
| CHUNK_OVERLAP | 80 | 相邻 Chunk 重叠字符数 |

## Embedding（双模式：mock / zhipu）

| 环境变量 | 默认值 | 说明 |
|------|------|------|
| EMBEDDING_DIMENSION | 384 | Mock 模式向量维度（legacy，仅 Mock 生效） |
| AI_EMBEDDING_PROVIDER | mock | Embedding 模式：`mock`（无 Key）/ `zhipu`（真实 API） |
| AI_EMBEDDING_MODEL | embedding-3 | 真实 Embedding 模型名 |
| AI_EMBEDDING_DIMENSIONS | 1024 | 真实 Embedding 维度 |
| AI_EMBEDDING_ENDPOINT | https://open.bigmodel.cn/api/paas/v4/embeddings | Embedding API 端点 |
| AI_EMBEDDING_API_KEY | (空) | 真实 API Key（也可用 ZHIPU_API_KEY；仅环境变量，绝不写代码） |
| AI_EMBEDDING_BATCH_SIZE | 16 | 批量大小（单批上限 64） |
| AI_EMBEDDING_CONNECT_TIMEOUT_SECONDS | 5 | 连接超时 |
| AI_EMBEDDING_READ_TIMEOUT_SECONDS | 30 | 读取超时 |
| AI_EMBEDDING_MAX_RETRIES | 2 | 对 429/5xx 的指数退避重试次数 |
| AI_EMBEDDING_FALLBACK_ENABLED | false | 缺 Key 时是否降级到 Mock（默认 false：明确失败，不静默降级） |

> **Mock 模式（默认）**：SHA-256 伪向量，384 维，无需 Key，写入 legacy Collection `kb_chunks`。
> **Real 模式（zhipu）**：智谱 `embedding-3`，1024 维，写入隔离 Collection `kb_chunks_zhipu_embedding_3_1024_v1`。
> 切换 provider 后需对文档重新索引；两种模式物理隔离，旧 Mock 数据保留。

### Mock 模式（默认）

```bash
# 无需额外配置
mvn spring-boot:run
```

### 智谱 GLM 真实 Chat 模式

```bash
export AI_MOCK_ENABLED=false
export AI_PROVIDER=zhipu
export ZHIPU_API_KEY='<your-local-api-key>'
export AI_API_BASE_URL='https://open.bigmodel.cn/api/paas/v4'
export AI_MODEL='glm-4.7-flash'

mvn spring-boot:run
```

> 可选推理模型：`export AI_MODEL='glm-z1-flash'`
>
> 注意：
> 1. 不要提交真实 API Key 到代码仓库。所有 Key 仅来自环境变量 `ZHIPU_API_KEY` / `AI_API_KEY`。
> 2. 真实 Chat 与真实 Embedding **解耦**，可分别开关。
> 3. 真实 Chat API 采用 OpenAI-compatible 接口，可切换阿里百炼、DeepSeek、火山方舟等兼容 OpenAI 的 provider。
> 4. 如果模型名不可用，可在智谱开放平台查看当前可用模型，并通过 AI_MODEL 替换。

### 智谱真实 Embedding 模式

```bash
export AI_EMBEDDING_PROVIDER=zhipu
export ZHIPU_API_KEY='<your-local-api-key>'
mvn spring-boot:run
```

> `fallback-enabled` 默认 `false`：缺 Key 时启动即失败（IllegalArgumentException），不静默降级。
> 状态查询：`GET /api/embedding/status` 仅返回 `apiKeyConfigured: true/false`，绝不返回 Key 本身。
> 详见 [docs/REAL_EMBEDDING.md](REAL_EMBEDDING.md)。

## AI Chat API

| 环境变量 | 默认值 | 说明 |
|------|------|------|
| AI_MOCK_ENABLED | true | 是否使用 Mock AI（true=Mock, false=真实 API） |
| AI_PROVIDER | mock | AI Provider 标识（mock / zhipu / deepseek 等） |
| AI_API_KEY | (空) | AI API Key（也可用 ZHIPU_API_KEY） |
| AI_API_BASE_URL | https://open.bigmodel.cn/api/paas/v4 | API 基础地址（OpenAI-compatible） |
| AI_MODEL | glm-4.7-flash | 模型名称 |
| AI_TIMEOUT_SECONDS | 30 | API 超时时间（秒） |
| AI_MAX_TOKENS | 1024 | 最大生成 Token 数 |
| AI_TEMPERATURE | 0.3 | 生成温度（0-1） |

### Mock 模式（默认）

```bash
# 无需额外配置
mvn spring-boot:run
```

### 智谱 GLM 真实 Chat 模式

```bash
export AI_MOCK_ENABLED=false
export AI_PROVIDER=zhipu
export ZHIPU_API_KEY='your_api_key'
export AI_API_BASE_URL='https://open.bigmodel.cn/api/paas/v4'
export AI_MODEL='glm-4.7-flash'

mvn spring-boot:run
```

> 可选推理模型：`export AI_MODEL='glm-z1-flash'`
>
> 注意：
> 1. 不要提交真实 API Key 到代码仓库。
> 2. 当前只接真实 Chat API，Embedding 仍为 Mock Embedding。
> 3. 真实 Chat API 采用 OpenAI-compatible 接口，后续可切换阿里百炼、DeepSeek、火山方舟等兼容 OpenAI 的 provider。
> 4. 如果模型名不可用，可在智谱开放平台查看当前可用模型，并通过 AI_MODEL 替换。

## Demo 数据清理

`scripts/reset_demo_data.sh` 用于清理本地 demo 产生的数据，清空 MySQL 表、uploads 文件和 Qdrant collection。

```bash
export DB_NAME=ai_knowledge_rag
export DB_USERNAME=ai_dev
export DB_PASSWORD='your_db_password'
export QDRANT_URL=http://localhost:6333
export QDRANT_COLLECTION=kb_chunks
export UPLOAD_DIR="$(pwd)/uploads"

./scripts/reset_demo_data.sh
./scripts/demo_rag_flow.sh
```

> 注意：仅用于本地 demo 环境，不建议在生产环境执行。会清空 RAG demo 相关 MySQL 表、uploads 文件和 Qdrant collection。

## 快速配置

参考 `.env.example` 文件：

```bash
cp .env.example .env
# 编辑 .env 填入真实密码
source .env
```

启动时也可以临时指定：

```bash
DB_PASSWORD=your_password mvn spring-boot:run
```

> 注意：`.env` 已加入 `.gitignore`，不会被提交到 Git。