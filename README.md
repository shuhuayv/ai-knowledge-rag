# ai-knowledge-rag

AI 知识库问答系统 - 基于 Spring AI + RAG（检索增强生成）的企业级知识库。

## 技术栈

- Java 21
- Spring Boot 4.1
- Maven
- MySQL
- Redis
- MyBatis-Plus
- Lombok
- Validation
- Springdoc OpenAPI / Swagger UI
- Qdrant（向量数据库）

## 已完成功能

- Spring Boot 基础项目
- MySQL 数据库（ai_knowledge_rag）
- Redis 基础配置
- MyBatis-Plus 集成
- 文档上传（TXT/PDF）
- 文档列表查询
- 文档分页查询
- 文档详情查询
- 文档删除（含文件删除）
- Controller / Service / Mapper 三层架构
- 统一返回结构 ApiResponse
- 通用分页返回对象 PageResult
- 全局异常处理
- 参数校验
- Swagger / OpenAPI 中文注解

## 本地启动

### 1. 配置环境变量

```bash
cp .env.example .env
# 编辑 .env，填入 DB_PASSWORD
source .env
```

详细配置说明见 [docs/configuration.md](docs/configuration.md)。

### 2. Mock 模式启动（默认，无需 API Key）

```bash
mvn spring-boot:run
```

### 3. 智谱 GLM 真实 Chat 模式启动

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
> 注意：不要提交真实 API Key。当前只接真实 Chat API，Embedding 仍为 Mock Embedding。
> 真实 Chat API 采用 OpenAI-compatible 接口，后续可切换阿里百炼、DeepSeek、火山方舟等兼容 OpenAI 的 provider。
> 如果模型名不可用，可在智谱开放平台查看当前可用模型，并通过 AI_MODEL 替换。

## Swagger 接口文档

项目启动后访问：

```
http://localhost:8080/swagger-ui.html
```

OpenAPI JSON 描述文件：

```
http://localhost:8080/v3/api-docs
```

## 接口列表

### 文档接口

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | /api/documents/upload | 上传文档（TXT/PDF） |
| GET | /api/documents | 查询文档列表 |
| GET | /api/documents/page?pageNum=1&pageSize=10 | 分页查询文档 |
| GET | /api/documents/{id} | 按 ID 查询文档详情 |
| DELETE | /api/documents/{id} | 删除文档（含文件） |
| POST | /api/documents/{id}/parse | 解析文档并切分 Chunk |
| GET | /api/documents/{id}/chunks | 查询文档的 Chunk 列表 |
| GET | /api/documents/{id}/chunks/page?pageNum=1&pageSize=10 | 分页查询文档的 Chunk |
| POST | /api/documents/{id}/index | 向量化文档（Mock Embedding + Qdrant） |
| GET | /api/documents/{id}/vectors | 查询文档的向量记录列表 |
| GET | /api/documents/{id}/vectors/page?pageNum=1&pageSize=10 | 分页查询文档的向量记录 |

### 检索接口

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | /api/search | 语义检索，返回 TopK 最相关 Chunk |

详见 [docs/search.md](docs/search.md)。

### RAG 问答接口

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | /api/rag/ask | RAG 问答（检索 + AI 生成回答 + 引用来源） |

详见 [docs/rag.md](docs/rag.md)。

## RAG 流程测试

```bash
source scripts/test_rag_flow.sh
```

详见 [docs/demo-script.md](docs/demo-script.md)。

## 一键脚本

| 脚本 | 说明 |
|------|------|
| `scripts/init_db.sh` | 初始化数据库 |
| `scripts/start_qdrant.sh` | 启动 Qdrant |
| `scripts/reset_demo_data.sh` | 清理 demo 数据（MySQL + Qdrant + uploads） |
| `scripts/test_rag_flow.sh` | 完整 RAG 流程测试 |
| `scripts/demo_rag_flow.sh` | RAG 流程演示（upload -> parse -> index -> search -> chat） |

### 演示前清理数据

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

> 注意：`reset_demo_data.sh` 仅用于本地 demo 环境，不建议在生产环境执行。会清空 RAG demo 相关 MySQL 表、uploads 文件和 Qdrant collection。

## 样例文档

`samples/company_policy.txt` — 虚拟公司制度文档，用于上传测试。

## 文档索引

| 文档 | 说明 |
|------|------|
| [docs/configuration.md](docs/configuration.md) | 环境变量配置说明 |
| [docs/runbook.md](docs/runbook.md) | 从零启动项目完整步骤 |
| [docs/demo-script.md](docs/demo-script.md) | 面试演示脚本 |
| [docs/database.md](docs/database.md) | 数据库表结构 |
| [docs/document-api.md](docs/document-api.md) | 文档管理接口 |
| [docs/parser.md](docs/parser.md) | 文档解析模块 |
| [docs/vector-index.md](docs/vector-index.md) | 向量化索引模块 |
| [docs/search.md](docs/search.md) | 语义检索接口 |
| [docs/rag.md](docs/rag.md) | RAG 问答模块 |
| [docs/qdrant.md](docs/qdrant.md) | Qdrant 部署说明 |
| [docs/roadmap.md](docs/roadmap.md) | 开发路线图 |

## 数据库表结构

| 表名 | 说明 |
|---|---|---|
| kb_document | 知识库文档表 |
| kb_chunk | 文档分块表 |
| ai_call_log | AI 调用日志表 |
| kb_vector_record | 向量记录表 |

详见 [docs/database.md](docs/database.md)。

## Qdrant 向量数据库

Qdrant 已通过 Docker 部署，启动命令见 [docs/qdrant.md](docs/qdrant.md)。

## 项目结构

```
src/main/java/com/shuhuayv/rag/
├── AiKnowledgeRagApplication.java
├── common/
│   ├── ApiResponse.java
│   └── PageResult.java
├── config/
│   └── OpenApiConfig.java
├── controller/
│   ├── KbDocumentController.java
│   ├── SearchController.java
│   └── RagController.java
├── dto/
│   ├── DocumentUploadResponse.java
│   ├── DocumentParseResponse.java
│   ├── DocumentIndexResponse.java
│   ├── SearchRequest.java
│   ├── SearchResultItem.java
│   ├── SearchResponse.java
│   ├── RagAskRequest.java
│   ├── RagReferenceItem.java
│   └── RagAskResponse.java
├── entity/
│   ├── KbDocument.java
│   ├── KbChunk.java
│   ├── AiCallLog.java
│   └── KbVectorRecord.java
├── exception/
│   └── GlobalExceptionHandler.java
├── mapper/
│   ├── KbDocumentMapper.java
│   ├── KbChunkMapper.java
│   ├── KbVectorRecordMapper.java
│   └── AiCallLogMapper.java
├── embedding/
│   └── service/
│       ├── EmbeddingService.java
│       └── impl/
│           └── MockEmbeddingServiceImpl.java
├── vector/
│   └── service/
│       ├── QdrantVectorService.java
│       └── impl/
│           └── QdrantVectorServiceImpl.java
└── service/
    ├── KbDocumentService.java
    ├── DocumentParseService.java
    ├── ChunkService.java
    ├── DocumentIndexService.java
    ├── SearchService.java
    ├── AiAnswerService.java
    ├── ChatModelService.java
    ├── PromptBuildService.java
    ├── RagService.java
└── impl/
        ├── KbDocumentServiceImpl.java
        ├── DocumentParseServiceImpl.java
        ├── ChunkServiceImpl.java
        ├── DocumentIndexServiceImpl.java
        ├── SearchServiceImpl.java
        ├── MockAiAnswerServiceImpl.java
        ├── MockChatModelServiceImpl.java
        ├── OpenAiCompatibleChatModelServiceImpl.java
        ├── PromptBuildServiceImpl.java
        └── RagServiceImpl.java
```

## 后续计划

详见 [docs/roadmap.md](docs/roadmap.md)。

## 当前状态

| 模块 | 状态 | 说明 |
|------|------|------|
| 文档上传 | 已完成 | TXT/PDF 上传入库 |
| 文档解析 | 已完成 | PDFBox 解析 PDF |
| Chunk 切分 | 已完成 | 滑动窗口 500 字符 + 80 重叠 |
| Mock Embedding | 已完成 | SHA-256 伪向量，384 维 |
| Qdrant 入库 | 已完成 | Cosine 相似度 |
| TopK 检索 | 已完成 | POST /api/search |
| Mock RAG 回答 | 已完成 | POST /api/rag/ask |
| AI 调用日志 | 已完成 | ai_call_log 表 |
| 真实大模型 | 已完成 | 智谱 GLM Chat API（OpenAI-compatible） |
| 真实 Embedding | 未完成 | 后续替换真实 API |
| 多轮会话 | 未完成 | 后续支持对话历史 |