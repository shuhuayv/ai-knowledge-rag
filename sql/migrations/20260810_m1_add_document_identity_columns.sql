-- ============================================================
-- M1：kb_document 新增「文档内容身份」列（PR-2A Document Identity）
-- 日期：2026-08-10
-- 关联：RAG PR-2A / feature/content-dedup
--
-- 🔴 NOT EXECUTED IN THIS ROUND —— 本轮仅提交文件与离线审查，禁止真实执行。
--    REAL_DB_MIGRATION_ALLOWED = NO
--
-- 【幂等性】YES。逐列检查 INFORMATION_SCHEMA.COLUMNS，列不存在才 ADD；
--          重复执行任意次结果一致，已存在的列打印 SKIP 且不做任何操作。
--          注：MySQL 8.0 不支持 `ADD COLUMN IF NOT EXISTS`（该语法为 MariaDB 扩展），
--          因此必须使用 INFORMATION_SCHEMA + PREPARE/EXECUTE。
--
-- 【破坏性】NO。纯 additive：只有 ALTER TABLE ... ADD COLUMN。
--          零 DROP / 零 TRUNCATE / 零 DELETE / 零 UPDATE；不修改任何既有行的既有列；
--          不做任何软删、不改 status、不动 file_path、不碰 Qdrant。
--
-- 【列序】  4 个新列一律追加到表末尾（不使用 AFTER）：不依赖当前物理列序，
--          部分应用状态下重跑仍成立，且 MySQL 8.0 可走 ALGORITHM=INSTANT。
--
-- 【兼容性】前向 + 后向兼容：
--          - 旧应用（PR-2 之前的 jar）不引用新列，MyBatis-Plus 生成显式列名清单而非 SELECT *，
--            INSERT 也不含 is_deleted → 由 DEFAULT 0 兜底，行为不变。
--          - ⚠️ 反向不成立：PR-2 之后的 jar 在未执行 M1 的库上会因 "Unknown column" 直接失败。
--            → 部署顺序硬约束：必须 **先执行 M1，再部署 PR-2 代码**。详见 04 文档 §8 风险表（MIG-R1）。
--
-- 【回滚】  sql/migrations/20260810_m1_rollback_document_identity_columns.sql
--          （带三重安全守卫，仅适用于 governance 尚未开始的阶段）
--
-- 【执行】  mysql -u ai_dev -p ai_knowledge_rag < sql/migrations/20260810_m1_add_document_identity_columns.sql
--          必须用 mysql CLI；不要加 --force（需要错误即中断）。
-- ============================================================

-- ------------------------------------------------------------
-- 预检（只读，可单独执行）：查看 4 个目标列当前是否已存在
-- ------------------------------------------------------------
-- SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT
--   FROM INFORMATION_SCHEMA.COLUMNS
--  WHERE TABLE_SCHEMA = DATABASE()
--    AND TABLE_NAME   = 'kb_document'
--    AND COLUMN_NAME IN ('content_sha256','is_deleted','canonical_document_id','dedup_batch');

SET @schema_name := DATABASE();

-- ------------------------------------------------------------
-- 1/4  content_sha256 CHAR(64) NULL
--      文档内容身份 = SHA-256(raw file bytes)，lowercase hex，64 字符。
--      注意：不是 Qdrant Point ID；Point ID 方案保持 UUID v3(documentId:chunkId:indexVersion) 不变。
-- ------------------------------------------------------------
SET @col_exists := (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = @schema_name
       AND TABLE_NAME   = 'kb_document'
       AND COLUMN_NAME  = 'content_sha256');

SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE kb_document ADD COLUMN content_sha256 CHAR(64) NULL COMMENT ''文档内容身份：SHA-256(raw file bytes)，lowercase hex 64 字符''',
    'SELECT ''SKIP: kb_document.content_sha256 already exists'' AS migration_note');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ------------------------------------------------------------
-- 2/4  is_deleted BIGINT NOT NULL DEFAULT 0
--      语义：0 = active；非 0 = 已软删（未来取值 = 该行自身 document id）。
--      因此实体侧禁止 @TableLogic（MP 的逻辑删除假定 0/1 常量，与本语义不符）。
--      对既有行：全部落到 DEFAULT 0（= active），语义正确，非数据篡改。
-- ------------------------------------------------------------
SET @col_exists := (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = @schema_name
       AND TABLE_NAME   = 'kb_document'
       AND COLUMN_NAME  = 'is_deleted');

SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE kb_document ADD COLUMN is_deleted BIGINT NOT NULL DEFAULT 0 COMMENT ''软删标记：0=active；非0=已软删（值为该行自身 document id）''',
    'SELECT ''SKIP: kb_document.is_deleted already exists'' AS migration_note');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ------------------------------------------------------------
-- 3/4  canonical_document_id BIGINT NULL
--      治理产物：软删副本指向被保留的 canonical document id。PR-2 只建列，不写值。
-- ------------------------------------------------------------
SET @col_exists := (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = @schema_name
       AND TABLE_NAME   = 'kb_document'
       AND COLUMN_NAME  = 'canonical_document_id');

SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE kb_document ADD COLUMN canonical_document_id BIGINT NULL COMMENT ''去重治理：指向保留下来的 canonical document id（PR-3 写入）''',
    'SELECT ''SKIP: kb_document.canonical_document_id already exists'' AS migration_note');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ------------------------------------------------------------
-- 4/4  dedup_batch VARCHAR(32) NULL
--      治理产物：去重批次号，用于审计与回溯。PR-2 只建列，不写值。
-- ------------------------------------------------------------
SET @col_exists := (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = @schema_name
       AND TABLE_NAME   = 'kb_document'
       AND COLUMN_NAME  = 'dedup_batch');

SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE kb_document ADD COLUMN dedup_batch VARCHAR(32) NULL COMMENT ''去重治理批次号（PR-3 写入），用于审计回溯''',
    'SELECT ''SKIP: kb_document.dedup_batch already exists'' AS migration_note');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ------------------------------------------------------------
-- 执行后自检（只读）
-- ------------------------------------------------------------
SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT, ORDINAL_POSITION
  FROM INFORMATION_SCHEMA.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE()
   AND TABLE_NAME   = 'kb_document'
   AND COLUMN_NAME IN ('content_sha256','is_deleted','canonical_document_id','dedup_batch')
 ORDER BY ORDINAL_POSITION;

-- 期望：恰好 4 行；
--   content_sha256        char(64)     YES  NULL
--   is_deleted            bigint       NO   0
--   canonical_document_id bigint       YES  NULL
--   dedup_batch           varchar(32)  YES  NULL
