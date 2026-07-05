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

### 1. 初始化数据库

```bash
mysql -uroot -p < sql/init.sql
```

### 2. 配置环境变量

参考 .env.example：

```bash
export DB_NAME=ai_knowledge_rag
export DB_USERNAME=ai_dev
export DB_PASSWORD=your_local_mysql_password
export REDIS_HOST=localhost
export REDIS_PORT=6379
export UPLOAD_DIR=uploads
```

也可以启动时临时指定：

```bash
DB_PASSWORD=your_local_mysql_password mvn spring-boot:run
```

### 3. 启动项目

```bash
mvn spring-boot:run
```

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

详见 [docs/document-api.md](docs/document-api.md)。

## 数据库表结构

| 表名 | 说明 |
|---|---|
| kb_document | 知识库文档表 |
| kb_chunk | 文档分块表 |
| ai_call_log | AI 调用日志表 |

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
│   └── KbDocumentController.java
├── dto/
│   └── DocumentUploadResponse.java
├── entity/
│   ├── KbDocument.java
│   ├── KbChunk.java
│   └── AiCallLog.java
├── exception/
│   └── GlobalExceptionHandler.java
├── mapper/
│   └── KbDocumentMapper.java
└── service/
    ├── KbDocumentService.java
    └── impl/
        └── KbDocumentServiceImpl.java
```

## 后续计划

详见 [docs/roadmap.md](docs/roadmap.md)。