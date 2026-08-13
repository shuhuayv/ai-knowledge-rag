-- ============================================================
-- M2 ROLLBACK：删除唯一索引 uk_document_content_sha256_is_deleted
-- 日期：2026-08-10
-- 关联：RAG PR-2A / feature/content-dedup
--
-- 🔴 NOT EXECUTED IN THIS ROUND —— 本轮仅提交文件与离线审查，禁止真实执行。
--    REAL_DB_MIGRATION_ALLOWED = NO
--
-- 【幂等性】YES。索引不存在则打印 SKIP，重复执行任意次结果一致、退出码 0。
--          注：MySQL 8.0 不支持原生 `DROP INDEX IF EXISTS`（MariaDB 扩展），
--          故用 INFORMATION_SCHEMA.STATISTICS 做等价实现。
--
-- 【破坏性】NO。只删除索引对象，不删除任何列、不修改任何行数据。
--          回滚后 content_sha256 / is_deleted 列及其数据完整保留，
--          应用层退回 PRE_M2 语义（best-effort application dedup）。
--
-- 【无需守卫的理由】删索引不丢数据、可随时用 M2 重建，属完全可逆操作；
--          唯一副作用是失去 DB 级并发唯一性保证，已在应用层 Javadoc 与 PR body 中标注为 PRE_M2 语义。
--
-- 【执行】 mysql -u ai_dev -p ai_knowledge_rag < sql/migrations/20260810_m2_rollback_unique_content_sha256_is_deleted.sql
-- ============================================================

SET @schema_name := DATABASE();

SET @index_exists := (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
     WHERE TABLE_SCHEMA = @schema_name
       AND TABLE_NAME   = 'kb_document'
       AND INDEX_NAME   = 'uk_document_content_sha256_is_deleted'
       AND SEQ_IN_INDEX = 1);

SET @ddl := IF(@index_exists > 0,
    'ALTER TABLE kb_document DROP INDEX uk_document_content_sha256_is_deleted',
    'SELECT ''SKIP: uk_document_content_sha256_is_deleted does not exist'' AS migration_note');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ------------------------------------------------------------
-- 执行后自检（只读）：期望返回 0 行
-- ------------------------------------------------------------
SELECT INDEX_NAME, SEQ_IN_INDEX, COLUMN_NAME
  FROM INFORMATION_SCHEMA.STATISTICS
 WHERE TABLE_SCHEMA = DATABASE()
   AND TABLE_NAME   = 'kb_document'
   AND INDEX_NAME   = 'uk_document_content_sha256_is_deleted';
