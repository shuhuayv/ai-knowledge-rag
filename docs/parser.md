# 文档解析模块

## 概述

文档解析模块负责将上传的 TXT 和 PDF 文件解析为纯文本内容，供后续 Chunk 切分和 Embedding 使用。

## 支持的文件格式

| 格式 | 解析方式 | 依赖 |
|------|---------|------|
| TXT | Java NIO `Files.readString()` | 无（JDK 内置） |
| PDF | Apache PDFBox 3.0.4 | `pdfbox` Maven 依赖 |

## 解析流程

1. 根据 `kb_document.file_path` 读取本地文件
2. 根据 `kb_document.file_type` 选择解析方式
3. 解析成功后更新 `kb_document.status` 为 `PARSED`
4. 解析失败后更新 `kb_document.status` 为 `FAILED`，并记录 `remark`

## 当前限制

- 仅支持 TXT 和 PDF 格式，不支持 DOCX、Markdown 等
- PDF 解析仅提取纯文本，不保留格式、表格、图片
- 不支持 OCR（扫描版 PDF 图片内容无法提取）
- 不支持加密 PDF

## Chunk 切分规则

| 参数 | 值 | 说明 |
|------|-----|------|
| 最大长度 | 500 字符 | 每个 Chunk 的字符上限 |
| 重叠长度 | 80 字符 | 相邻 Chunk 的重叠区域 |
| 索引 | 从 0 开始 | Chunk 序号 |
| Token 计数 | content.length() | 当前用字符数近似 Token |

## 相关接口

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /api/documents/{id}/parse | 解析文档并切分 Chunk |
| GET | /api/documents/{id}/chunks | 查询文档 Chunk 列表 |
| GET | /api/documents/{id}/chunks/page | 分页查询文档 Chunk |

## 后续扩展

- 支持 DOCX、Markdown 等更多格式
- 更智能的 Chunk 切分策略（按段落/句子边界）
- 接入 Embedding 模型生成向量