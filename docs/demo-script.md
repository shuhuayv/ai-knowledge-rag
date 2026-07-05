# 面试演示脚本

## 演示目标

展示从文档上传到 RAG 问答的完整工程闭环。

## 演示步骤

### 1. 打开 Swagger，展示接口列表

打开 `http://localhost:8080/swagger-ui.html`，展示以下接口分组：

- 文档管理接口
- 文档解析接口
- 文档向量化接口
- 语义检索接口
- RAG 问答接口

### 2. 上传样例文档

- 选择 `samples/company_policy.txt`
- 调用 POST `/api/documents/upload`
- 展示返回：文档 ID、文件名、文件类型、状态

### 3. 解析文档

- 调用 POST `/api/documents/{id}/parse`
- 展示返回：Chunk 数量、状态 PARSED

### 4. 向量化文档

- 调用 POST `/api/documents/{id}/index`
- 展示返回：向量数量、collection 名称、状态 INDEXED
- 说明：384 维 Mock Embedding，写入 Qdrant

### 5. 语义检索

- 调用 POST `/api/search`
- 请求体：`{"query": "公司报销制度是什么？", "topK": 5}`
- 展示返回：TopK 个 Chunk，每个包含内容、相似度分数

### 6. RAG 问答

- 调用 POST `/api/rag/ask`
- 请求体：`{"question": "公司报销制度是什么？", "topK": 5}`
- 展示返回：
  - answer：AI 生成的回答
  - references：引用来源（文档 ID、Chunk 内容、相似度）
  - promptPreview：构建的 Prompt 预览
  - costMs：查询耗时

### 7. 展示 ai_call_log

- 查看 `ai_call_log` 表，展示每次调用的日志记录

## 可提问的问题

| 问题 | 预期能检索到的内容 |
|------|------|
| 公司报销制度是什么？ | 报销制度、差旅标准 |
| 员工年假有多少天？ | 年假制度、入职年限 |
| 信息安全有什么要求？ | 密码管理、数据保护 |
| 出差住宿标准是什么？ | 出差制度、住宿标准 |

## 演示要点

1. 强调完整工程闭环：上传 → 解析 → 向量化 → 检索 → 生成
2. 说明 Mock Embedding 和 Mock AI 是工程验证，后续可替换真实 API
3. 强调 `ai_call_log` 的日志记录能力
4. 展示配置化：所有参数通过环境变量控制