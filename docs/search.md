# 语义检索接口

## 概述

语义检索接口基于向量相似度，从 Qdrant 向量数据库中检索与用户查询最相关的 TopK 个文档 Chunk。
支持 **Mock / 真实双模式 Embedding**（维度与 Collection 由 `EmbeddingService` + `CollectionNameResolver` 决定）。

> 语义检索是 RAG 问答的前置步骤。检索结果会作为上下文传递给 AI 模型生成回答。详见 [docs/rag.md](rag.md)。

## 检索流程

```
用户查询 "什么是RAG？"
       ↓
EmbeddingService.embed(query) → 向量（Mock=384 / zhipu=1024）
       ↓
CollectionNameResolver 解析 Collection（Mock= kb_chunks / Real= kb_chunks_zhipu_embedding_3_1024_v1）
       ↓
QdrantVectorService.search(collection, vector, topK)
       ↓
Qdrant HTTP API: POST /collections/{collection}/points/search
       ↓
过滤（score >= rag.retrieval.min-score，默认 0.0 不过滤）
       ↓
返回 TopK 个最相似 Chunk（documentId, chunkId, content, score）
       ↓
组装 SearchResponse（含 candidate / returned 计数）
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
    "costMs": 123,
    "retrievalCandidateCount": 5,
    "retrievalReturnedCount": 3
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
| retrievalCandidateCount | int | min-score 过滤前候选数量 |
| retrievalReturnedCount | int | min-score 过滤后返回数量 |

> `rag.retrieval.min-score`（默认 0.0）控制过滤阈值：仅 `score >= minScore` 的结果返回；
> `retrievalCandidateCount` / `retrievalReturnedCount` 用于透明诊断"为何返回变少"。

## 当前限制

1. **Mock Embedding 无真实语义**：仅 Mock 模式检索结果无真实语义，仅为工程闭环验证；真实评估请用 `zhipu` 模式。
2. **无结果处理**：若 Qdrant 无数据或全被 min-score 过滤，返回空列表（RAG 层会给出固定引导文案，不调用 Chat）。
3. **不支持过滤**：不支持按文档 ID、文件类型等条件过滤。
4. **不支持重排序**：无 Rerank 步骤（score 为向量相似度，非答案正确率）。

## 后续扩展

- 替换 Mock Embedding 为真实 Embedding 模型，获得真实语义检索结果
- 在 RAG 问答中，将检索结果作为上下文传给大模型
- 支持按文档、文件类型等条件过滤
- 接入 Rerank 模型提升检索精度