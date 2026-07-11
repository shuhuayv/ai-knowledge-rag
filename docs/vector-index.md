# 向量化索引模块

## 概述

向量化索引模块负责将文档 Chunk 通过 `EmbeddingService` 生成向量，并写入 Qdrant 向量数据库。
支持**双模式**：Mock（SHA-256 伪向量，384 维，无 Key）与真实智谱 `embedding-3`（1024 维，需 Key）。
两种模式使用不同 Qdrant Collection **物理隔离**，旧 Mock 数据（`kb_chunks`）保留。

## 架构流程

```
Chunk → EmbeddingService.embed() → 向量（Mock=384 / zhipu=1024）
                                     ↓
                              CollectionNameResolver 解析 Collection（Mock/Real 隔离）
                                     ↓
                              QdrantVectorService.upsertPoint()
                                     ↓
                              Qdrant（kb_chunks 或 kb_chunks_zhipu_embedding_3_1024_v1）
                                     ↓
                              kb_vector_record 记录入库（含 embedding 元数据）
```

## 相关接口

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /api/documents/{id}/index | 向量化文档 |
| GET | /api/documents/{id}/vectors | 查询向量记录列表 |
| GET | /api/documents/{id}/vectors/page | 分页查询向量记录 |

## 向量化文档

```
POST /api/documents/{id}/index
```

功能：对指定文档的所有 Chunk 生成 Mock Embedding 向量，并写入 Qdrant。

流程：
1. 检查文档是否存在，且已解析（有 Chunk）
2. 确保 Qdrant Collection `kb_chunks` 存在（自动创建）
3. 删除旧的 kb_vector_record 记录
4. 逐 Chunk 调用 EmbeddingService 生成向量
5. 写入 Qdrant（Upsert Point）
6. 保存 kb_vector_record 记录
7. 更新 kb_document.status 为 INDEXED

响应示例：

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "documentId": 1,
    "status": "INDEXED",
    "chunkCount": 15,
    "vectorCount": 15,
    "collectionName": "kb_chunks",
    "message": "文档向量化完成，共写入 15 条向量到 Qdrant Collection: kb_chunks"
  }
}
```

## Mock Embedding 实现

| 属性 | 值 |
|------|-----|
| 向量维度 | 384 |
| 生成方式 | SHA-256 对文本 hash，映射到 384 维浮点数 |
| 归一化 | L2 归一化 |
| 相同文本 | 输出稳定（相同输入 → 相同向量） |
| 外部 API | 不调用，完全本地计算 |

> 真实模式（`zhipu`）：调用智谱 `embedding-3`，1024 维，批量（batch-size 默认 16，单批 ≤64）、
> 对 429/5xx 指数退避重试、响应严格校验（维度/顺序/NaN/Inf）。详见 [docs/REAL_EMBEDDING.md](REAL_EMBEDDING.md)。

## 索引元数据（透明性增强）

索引时写入：

- `kb_vector_record`：`embedding_provider` / `embedding_model` / `embedding_dimensions` / `index_version`
- `kb_document`：`embedding_provider` / `embedding_model` / `embedding_dimensions` / `vector_collection` / `index_version` / `indexed_at`
- **确定性 pointId**：`{documentId}_{chunkId}_{indexVersion}`（如 `1_10_v1`），重索引幂等（覆盖同一 Point）。

## Qdrant 配置

| 配置项 | 值 | 环境变量 |
|------|-----|------|
| 地址 | http://localhost:6333 | QDRANT_HOST |
| Collection（Mock） | kb_chunks | QDRANT_COLLECTION（legacy） |
| Collection（Real） | kb_chunks_zhipu_embedding_3_1024_v1 | 由 CollectionNameResolver 解析 |
| 维度 | Mock=384 / zhipu=1024（来自 `EmbeddingService.dimensions()`） | 不再固定 |
| 距离度量 | Cosine | 固定 |

## Qdrant Point Payload 结构

```json
{
  "documentId": 1,
  "chunkId": 1,
  "chunkIndex": 0,
  "content": "这是一段文档内容..."
}
```

## 当前限制

1. **Mock Embedding 无真实语义**：仅 Mock 模式（默认）使用 SHA-256 伪向量，TopK 结果无意义；真实评估请用 `zhipu` 模式。
2. **Collection 维度一致性强约束**：`ensureCollection` 对已存在 Collection 校验维度/距离，不一致明确抛异常（绝不自动重建）。切换 provider 后需对文档重新索引，或对 Collection 手动重建。
3. **重新向量化**：重新索引会删除该文档的 `kb_vector_record` 表记录并重写（确定性 pointId 覆盖 Qdrant Point）。
4. **安全边界**：API Key 仅来自环境变量，状态接口只返回 `apiKeyConfigured: true/false`，绝不返回 Key。

## 后续替换真实 Embedding

1. 设置 `AI_EMBEDDING_PROVIDER=zhipu` 并注入 `ZHIPU_API_KEY` / `AI_API_KEY`
2. 对文档重新执行索引（`POST /api/documents/{id}/index`），写入隔离 Collection
3. 检索与 RAG 自动走真实模式（Collection 解析由 `CollectionNameResolver` 统一负责）
4. 用 `scripts/evaluate_real_embedding.sh` 评估 hit@1/hit@3/MRR

## 检索流程

向量入库完成后，可通过 `POST /api/search` 进行语义检索：

```
用户查询 → EmbeddingService.embed(query) → QdrantVectorService.search("kb_chunks", vector, topK)
                                                ↓
                                         Qdrant Cosine 相似度排序
                                                ↓
                                         返回 TopK SearchResultItem
```

详见 [docs/search.md](search.md)。

## 后续替换真实 Embedding

1. 实现 `EmbeddingService` 新实现类，调用真实 Embedding API
2. 修改 `application.yml` 配置，注入真实实现
3. 调整向量维度匹配真实模型（如 OpenAI text-embedding-3-small 为 1536 维）
4. 更新 Qdrant Collection 维度配置