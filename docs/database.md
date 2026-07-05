# 数据库表结构

## 数据库

- 数据库名：`ai_knowledge_rag`
- 字符集：`utf8mb4`
- 排序规则：`utf8mb4_unicode_ci`

## 初始化

```bash
mysql -uroot -p < sql/init.sql
```

## 表结构

### kb_document（知识库文档表）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGINT | 主键，自增 |
| file_name | VARCHAR(255) | 文件名 |
| file_type | VARCHAR(32) | 文件类型（TXT/PDF） |
| file_path | VARCHAR(512) | 文件存储路径 |
| file_size | BIGINT | 文件大小（字节） |
| status | VARCHAR(32) | 状态：UPLOADED/PARSING/PARSED/FAILED |
| remark | VARCHAR(512) | 备注 |
| created_at | DATETIME | 创建时间 |
| updated_at | DATETIME | 更新时间 |

### kb_chunk（文档分块表）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGINT | 主键，自增 |
| document_id | BIGINT | 关联文档 ID |
| content | TEXT | 分块内容 |
| chunk_index | INT | 分块序号 |
| page_no | INT | 页码（PDF） |
| token_count | INT | Token 数量 |
| created_at | DATETIME | 创建时间 |

### ai_call_log（AI 调用日志表）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGINT | 主键，自增 |
| api_type | VARCHAR(64) | API 类型（EMBEDDING/CHAT/RERANK） |
| request_summary | TEXT | 请求摘要 |
| response_summary | TEXT | 响应摘要 |
| cost_ms | BIGINT | 耗时（毫秒） |
| status | VARCHAR(32) | 状态：SUCCESS/FAILED |
| created_at | DATETIME | 创建时间 |