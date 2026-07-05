# 向量化索引模块

## 概述

向量化索引模块负责将文档 Chunk 通过 Embedding 模型生成向量，并写入 Qdrant 向量数据库。当前为 Mock Embedding 基础版，跑通工程闭环。

## 架构流程

```
Chunk → EmbeddingService.embed() → 384维向量
                                     ↓
                              QdrantVectorService.upsertPoint()
                                     ↓
                              Qdrant (kb_chunks collection)
                                     ↓
                              kb_vector_record 记录入库
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

## Qdrant 配置

| 配置项 | 值 | 环境变量 |
|------|-----|------|
| 地址 | http://localhost:6333 | QDRANT_HOST |
| Collection | kb_chunks | QDRANT_COLLECTION |
| 维度 | 384 | 固定 |
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

1. **Mock Embedding**：当前使用 SHA-256 伪向量，无真实语义，TopK 检索结果无意义
2. **不删除旧 Qdrant Point**：重新向量化时只删除 kb_vector_record 表记录，不删除 Qdrant 中已存在的 Point（Qdrant 中会累积旧数据）
3. **不支持批量**：逐 Chunk 串行 Embedding + Upsert，大数据量下性能较差
4. **不支持检索**：当前仅完成向量入库，尚未实现 TopK 检索

## 后续替换真实 Embedding

1. 实现 `EmbeddingService` 新实现类，调用真实 Embedding API
2. 修改 `application.yml` 配置，注入真实实现
3. 调整向量维度匹配真实模型（如 OpenAI text-embedding-3-small 为 1536 维）
4. 更新 Qdrant Collection 维度配置