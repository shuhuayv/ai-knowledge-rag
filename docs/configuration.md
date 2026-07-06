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