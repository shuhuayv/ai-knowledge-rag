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

## Embedding

| 环境变量 | 默认值 | 说明 |
|------|------|------|
| EMBEDDING_DIMENSION | 384 | 向量维度 |

> 当前 Embedding 仍为 Mock Embedding，未接入真实 Embedding API。

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