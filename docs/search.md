# 语义检索接口

## 概述

语义检索接口基于向量相似度，从 Qdrant 向量数据库中检索与用户查询最相关的 TopK 个文档 Chunk。

## 检索流程

```
用户查询 "什么是RAG？"
       ↓
EmbeddingService.embed(query) → 384 维 Mock 向量
       ↓
QdrantVectorService.search(collection="kb_chunks", vector, topK)
       ↓
Qdrant HTTP API: POST /collections/kb_chunks/points/search
       ↓
返回 TopK 个最相似 Chunk（documentId, chunkId, content, score）
       ↓
组装 SearchResponse
```

## 接口

```
POST /api/search
```

### 请求

```json
{
  "query": "什么是RAG？",
  "topK": 5
}
```

| 参数 | 类型 | 必填 | 默认值 | 说明 |
|------|------|------|------|------|
| query | String | 是 | - | 查询内容 |
| topK | Integer | 否 | 5 | 返回结果数量（1-20） |

### 响应

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "query": "什么是RAG？",
    "topK": 5,
    "resultCount": 3,
    "results": [
      {
        "documentId": 1,
        "chunkId": 2,
        "chunkIndex": 0,
        "content": "RAG（Retrieval-Augmented Generation）是一种...",
        "score": 0.82,
        "collectionName": "kb_chunks"
      }
    ],
    "costMs": 123
  }
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| query | String | 查询内容 |
| topK | int | 请求的 TopK 数量 |
| resultCount | int | 实际返回结果数量 |
| results | List | 检索结果列表 |
| results[].documentId | Long | 文档 ID |
| results[].chunkId | Long | Chunk ID |
| results[].chunkIndex | Integer | Chunk 序号 |
| results[].content | String | Chunk 内容 |
| results[].score | Double | 相似度分数（Cosine） |
| results[].collectionName | String | Qdrant Collection 名称 |
| costMs | long | 检索耗时（毫秒） |

## 当前限制

1. **Mock Embedding**：当前使用 SHA-256 伪向量，检索结果无真实语义，仅为工程闭环验证
2. **无结果处理**：如果 Qdrant 中无数据或匹配度低，返回空列表（不报错）
3. **不支持过滤**：不支持按文档 ID、文件类型等条件过滤
4. **不支持重排序**：无 Rerank 步骤

## 后续扩展

- 替换 Mock Embedding 为真实 Embedding 模型，获得真实语义检索结果
- 在 RAG 问答中，将检索结果作为上下文传给大模型
- 支持按文档、文件类型等条件过滤
- 接入 Rerank 模型提升检索精度