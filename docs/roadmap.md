# 开发路线图

## 第一阶段（已完成）

- [x] 项目初始化
- [x] 数据库设计（kb_document / kb_chunk / ai_call_log）
- [x] 文档上传（TXT/PDF）
- [x] 文档 CRUD 接口
- [x] 分页查询
- [x] Swagger 接口文档
- [x] Qdrant 环境准备

## 第二阶段（已完成）

- [x] TXT 文件内容解析
- [x] PDFBox 解析 PDF 文件内容
- [x] 文档状态更新（UPLOADED → PARSED / FAILED）

## 第三阶段（已完成）

- [x] 文档内容 Chunk 切分
- [x] 滑动窗口切分策略（500 字符 + 80 字符 overlap）
- [x] Chunk 入库（kb_chunk 表）

## 第四阶段（已完成）

- [x] Mock Embedding 模块（SHA-256 伪向量，384 维）
- [x] Qdrant 客户端（RestClient，Collection 创建 + Point Upsert）
- [x] 向量化业务流程（Chunk → Embedding → Qdrant）
- [x] kb_vector_record 表记录
- [x] 文档状态更新（PARSED → INDEXED / FAILED）

## 第五阶段

- [ ] TopK 相似性检索
- [ ] RAG 问答接口
- [ ] 引用来源返回
- [ ] AI 调用日志记录（ai_call_log 表）

## 第六阶段

- [ ] 支持更多文件格式（DOCX/Markdown）
- [ ] 性能优化
- [ ] 接口限流
- [ ] 用户认证