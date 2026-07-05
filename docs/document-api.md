# 文档管理接口

## 接口列表

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | /api/documents/upload | 上传文档 |
| GET | /api/documents | 查询文档列表 |
| GET | /api/documents/page | 分页查询文档 |
| GET | /api/documents/{id} | 查询文档详情 |
| DELETE | /api/documents/{id} | 删除文档 |
| POST | /api/documents/{id}/parse | 解析文档并切分 Chunk |
| GET | /api/documents/{id}/chunks | 查询文档 Chunk 列表 |
| GET | /api/documents/{id}/chunks/page | 分页查询文档 Chunk |

## 上传文档

```
POST /api/documents/upload
Content-Type: multipart/form-data
```

参数：

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| file | MultipartFile | 是 | 文件（TXT/PDF） |

限制：
- 仅支持 TXT 和 PDF 格式
- 文件大小不超过 50MB

响应示例：

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "id": 1,
    "fileName": "企业知识库使用手册.pdf",
    "fileType": "PDF",
    "fileSize": 102400,
    "status": "UPLOADED",
    "createdAt": "2026-07-05T17:00:00"
  }
}
```

## 查询文档列表

```
GET /api/documents
```

返回所有文档，按 ID 倒序排列。

## 分页查询文档

```
GET /api/documents/page?pageNum=1&pageSize=10
```

参数：

| 参数 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| pageNum | long | 1 | 页码 |
| pageSize | long | 10 | 每页条数 |

## 查询文档详情

```
GET /api/documents/{id}
```

## 删除文档

```
DELETE /api/documents/{id}
```

删除数据库记录，同时尝试删除本地文件。如果文件不存在，仅删除数据库记录。

## 解析文档

```
POST /api/documents/{id}/parse
```

功能：解析指定文档内容，并自动切分为 Chunk 存入 `kb_chunk` 表。

- 支持 TXT 和 PDF 格式
- 解析成功后文档状态更新为 `PARSED`
- 解析失败后文档状态更新为 `FAILED`
- 重新解析时会先删除旧的 Chunk 再重新生成

响应示例：

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "documentId": 1,
    "fileName": "企业知识库使用手册.pdf",
    "status": "PARSED",
    "chunkCount": 15,
    "message": "文档解析并切分完成，共生成 15 个 Chunk"
  }
}
```

## 查询文档 Chunk 列表

```
GET /api/documents/{id}/chunks
```

返回指定文档的所有 Chunk，按 `chunkIndex` 升序排列。

## 分页查询文档 Chunk

```
GET /api/documents/{id}/chunks/page?pageNum=1&pageSize=10
```

参数：

| 参数 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| pageNum | long | 1 | 页码 |
| pageSize | long | 10 | 每页条数 |

## 向量化文档

```
POST /api/documents/{id}/index
```

功能：将文档 Chunk 生成 Mock Embedding 向量并写入 Qdrant。

前置条件：文档已解析（有 Chunk），Qdrant 已启动。

## 查询文档向量记录

```
GET /api/documents/{id}/vectors
```

返回指定文档在 Qdrant 中的向量记录列表。

## 分页查询向量记录

```
GET /api/documents/{id}/vectors/page?pageNum=1&pageSize=10
```

参数：

| 参数 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| pageNum | long | 1 | 页码 |
| pageSize | long | 10 | 每页条数 |