# RAG 问答模块

## 概述

RAG（Retrieval-Augmented Generation）问答模块将语义检索与 AI 生成结合，实现"检索 → 上下文构建 → 生成回答"的完整闭环。

## RAG 问答流程

```
用户问题 "这份文档主要讲了什么？"
       ↓
SearchService.search(question, topK=5)
       ↓
Qdrant 向量检索 → 返回 TopK 相关 Chunk
       ↓
PromptBuildService.buildPrompt(question, references)
       ↓
构建完整 Prompt（角色 + 指令 + 问题 + 检索片段）
       ↓
AiAnswerService.generateAnswer(question, references)
       ↓
Mock AI 生成回答（基于检索片段摘要）
       ↓
组装 RagAskResponse（回答 + 引用来源 + 耗时）
       ↓
写入 ai_call_log 表（api_type=RAG_ASK）
```

## 接口

```
POST /api/rag/ask
```

### 请求示例

```json
{
  "question": "这份文档主要讲了什么？",
  "topK": 5
}
```

| 参数 | 类型 | 必填 | 默认值 | 说明 |
|------|------|------|------|------|
| question | String | 是 | - | 用户问题 |
| topK | Integer | 否 | 5 | 检索 TopK 数量（1-20） |

### 响应示例

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "question": "这份文档主要讲了什么？",
    "answer": "根据已检索到的 3 个文档片段，该文档主要涉及...",
    "topK": 5,
    "referenceCount": 3,
    "references": [
      {
        "documentId": 1,
        "chunkId": 2,
        "chunkIndex": 0,
        "content": "RAG（Retrieval-Augmented Generation）是一种...",
        "score": 0.82
      }
    ],
    "promptPreview": "你是企业知识库问答助手。\n请基于以下已检索到的...",
    "costMs": 456
  }
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| question | String | 用户问题 |
| answer | String | AI 生成的回答 |
| topK | int | 检索 TopK 数量 |
| referenceCount | int | 引用来源数量 |
| references | List | 引用来源列表 |
| references[].documentId | Long | 文档 ID |
| references[].chunkId | Long | Chunk ID |
| references[].chunkIndex | Integer | Chunk 序号 |
| references[].content | String | Chunk 内容 |
| references[].score | Double | 相似度分数 |
| promptPreview | String | Prompt 预览（前 500 字符） |
| costMs | long | 问答耗时（毫秒） |

## Prompt 构建策略

1. **角色设定**：你是企业知识库问答助手
2. **约束条件**：只能基于给定资料作答，不要编造
3. **用户问题**：原样保留
4. **检索片段**：按相似度排序，包含来源信息
5. **输出指令**：用中文简洁回答

## AI 回答生成

### Mock 实现（当前）

- 有检索结果时：基于检索片段摘要生成结构化回答，标注来源和相似度
- 无检索结果时：提示"未在知识库中找到足够相关的内容"
- 末尾标注 Mock AI 提示

### 后续替换真实大模型

1. 实现 `AiAnswerService` 新实现类（如 `OpenAiAnswerServiceImpl`）
2. 将 Prompt 发送给大模型 API（如 OpenAI、DeepSeek 等）
3. 解析大模型返回的文本作为回答
4. 接口无需改动，只需替换实现类

## AI 调用日志

每次 RAG 问答自动写入 `ai_call_log` 表：

| 字段 | 值 |
|------|-----|
| api_type | RAG_ASK |
| request_summary | question + topK |
| response_summary | answer 前 500 字符 |
| cost_ms | 实际耗时 |
| status | SUCCESS / FAILED |

## 当前限制

1. **Mock AI 回答**：基于检索片段拼凑，不是真实大模型生成
2. **无对话历史**：每次问答独立，不支持多轮对话
3. **无流式输出**：返回完整回答，不支持 SSE 流式
4. **无 Rerank**：检索结果直接使用，无重排序