-- ============================================================
-- 迁移脚本：真实 Embedding 接入（智谱 embedding-3，1024 维）元数据增强
-- 日期：2026-07-10
-- 说明：
--   1. 接入真实 Embedding 后，需要记录每个文档/向量使用的 provider、model、
--      维度、Collection、索引版本，以及 AI 调用日志的非敏感元数据。
--   2. 仅为增量 DDL，安全、不 DROP、不删除任何旧 Mock 数据（384 维 kb_chunks 保留）。
--   3. 384（mock）与 1024（real）使用不同 Qdrant Collection 隔离，互不影响。
--
-- 执行前建议：
--   * 对 ai_knowledge_rag 数据库做一次备份（如 mysqldump）。
--   * 在本地/测试环境先执行验证，再上生产。
--
-- 使用方式：
--   mysql -u ai_dev -p ai_knowledge_rag < sql/migrations/20260710_add_real_embedding_metadata.sql
--
-- 注意：
--   * MySQL 不支持可靠的 `ADD COLUMN IF NOT EXISTS`，因此使用普通 ADD COLUMN。
--   * MySQL 不支持可靠的 `ADD COLUMN IF NOT EXISTS`。若列已存在，对应 ALTER 会报 "Duplicate column" 错误，
--     需按列跳过该条 ALTER（不要整脚本忽略）；本脚本保持不 DROP/不删数据。
--   * 本脚本不修改已有数据，仅新增可空列，回滚简单（DROP COLUMN 即可）。
-- ============================================================

-- 预检（示例）：执行前先确认列是否已存在，已存在则跳过对应 ALTER
-- SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS
--   WHERE TABLE_SCHEMA = 'ai_knowledge_rag' AND TABLE_NAME = 'kb_document'
--     AND COLUMN_NAME = 'embedding_provider';

-- ----------------------------
-- kb_document：真实 Embedding 索引元数据
-- ----------------------------
ALTER TABLE kb_document
    ADD COLUMN embedding_provider VARCHAR(64) NULL COMMENT 'Embedding Provider（mock / zhipu）' AFTER remark,
    ADD COLUMN embedding_model VARCHAR(128) NULL COMMENT 'Embedding 模型（embedding-3 / mock）' AFTER embedding_provider,
    ADD COLUMN embedding_dimensions INT NULL COMMENT 'Embedding 维度（384=mock, 1024=zhipu）' AFTER embedding_model,
    ADD COLUMN vector_collection VARCHAR(128) NULL COMMENT '向量所在 Qdrant Collection 名称' AFTER embedding_dimensions,
    ADD COLUMN index_version VARCHAR(32) NULL COMMENT '索引版本（v1，用于确定性 pointId 与重索引识别）' AFTER vector_collection,
    ADD COLUMN indexed_at DATETIME NULL COMMENT '最近一次成功索引时间' AFTER index_version;

-- ----------------------------
-- kb_vector_record：Embedding 元数据
-- ----------------------------
ALTER TABLE kb_vector_record
    ADD COLUMN embedding_provider VARCHAR(64) NULL COMMENT 'Embedding Provider（mock / zhipu）' AFTER vector_dimension,
    ADD COLUMN embedding_model VARCHAR(128) NULL COMMENT 'Embedding 模型（embedding-3 / mock）' AFTER embedding_provider,
    ADD COLUMN embedding_dimensions INT NULL COMMENT 'Embedding 维度（384=mock, 1024=zhipu）' AFTER embedding_model,
    ADD COLUMN index_version VARCHAR(32) NULL COMMENT '索引版本（v1，与 pointId 关联）' AFTER embedding_dimensions;

-- ----------------------------
-- ai_call_log：非敏感元数据 JSON
-- ----------------------------
ALTER TABLE ai_call_log
    ADD COLUMN metadata TEXT COMMENT '非敏感元数据 JSON（provider/model/dimensions/latencyMs/success/tokenUsage 等，不含任何 API Key）' AFTER status;
