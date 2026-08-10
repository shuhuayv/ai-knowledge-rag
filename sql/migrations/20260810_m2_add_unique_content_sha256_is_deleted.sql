-- ============================================================
-- M2：kb_document 增加最终唯一约束 UNIQUE(content_sha256, is_deleted)
-- 日期：2026-08-10
-- 关联：RAG PR-2A / feature/content-dedup
--
-- 🔴 NOT EXECUTED IN THIS ROUND —— 本轮仅提交文件与离线审查，禁止真实执行。
--    REAL_DB_MIGRATION_ALLOWED = NO
--
-- 【语义】
--   - active 行（is_deleted = 0）：同一 content_sha256 最多 1 行 → 内容级去重被 DB 强制。
--   - 软删行（is_deleted = 自身 document id，天然互不相同）：同 hash 的多个历史副本可共存。
--   - content_sha256 IS NULL 的行：MySQL 唯一索引把 NULL 视为互不相等，
--     因此尚未 backfill 的历史行不会阻塞索引创建，也不会互相冲突。
--
-- 【幂等性】YES。索引已存在则打印 SKIP 并跳过（INFORMATION_SCHEMA.STATISTICS 检查）。
--
-- 【破坏性】NO。只 ADD UNIQUE INDEX，不修改任何行数据。
--          中止路径同样不删数据 / 不自动选 canonical / 不静默继续。
--
-- 【前置条件】(推荐执行顺序：M1 → backfill(NULL=0) → M2)
--   1. M1 已执行（content_sha256 / is_deleted 两列存在）—— 脚本内 GUARD-0 强制。
--   2. 不存在 active 重复组 —— 脚本内 GUARD-1 强制中止（规格第9节）。
--   3. 建议 active 行 content_sha256 NULL 数 = 0 —— 脚本内 REPORT-2 仅报告不中止，
--      理由见 04_MIGRATION_REVIEW.md §5.3。
--
-- 【中止机制】SIGNAL SQLSTATE '45000'（只能写在存储程序内，故使用临时存储过程）。
--   mysql CLI 在不加 --force 时遇错立即以非 0 退出码中断，ALTER 绝不会被执行。
--   ⚠️ 若因 GUARD 中止，临时过程 sp_m2_add_unique_content_identity 可能残留；
--      脚本开头已 DROP PROCEDURE IF EXISTS，重跑自动清理；也可手动 DROP。
--
-- 【回滚】 sql/migrations/20260810_m2_rollback_unique_content_sha256_is_deleted.sql
--
-- 【执行】 mysql -u ai_dev -p ai_knowledge_rag < sql/migrations/20260810_m2_add_unique_content_sha256_is_deleted.sql
--         必须用 mysql CLI（脚本使用 DELIMITER）；不要加 --force。
-- ============================================================

-- ------------------------------------------------------------
-- DRY-RUN 预检（只读，强烈建议先单独执行这两条）
-- ------------------------------------------------------------
-- (1) active 重复组明细 —— 必须返回 0 行才允许执行 M2
-- SELECT content_sha256, COUNT(*) AS active_rows, GROUP_CONCAT(id ORDER BY id) AS document_ids
--   FROM kb_document
--  WHERE is_deleted = 0 AND content_sha256 IS NOT NULL
--  GROUP BY content_sha256
-- HAVING COUNT(*) > 1;
--
-- (2) 尚未 backfill 的 active 行 —— 治理前应为 0
-- SELECT COUNT(*) AS active_null_hash_rows
--   FROM kb_document WHERE is_deleted = 0 AND content_sha256 IS NULL;

DROP PROCEDURE IF EXISTS sp_m2_add_unique_content_identity;

DELIMITER $$

CREATE PROCEDURE sp_m2_add_unique_content_identity()
proc_label: BEGIN
    DECLARE v_schema        VARCHAR(64);
    DECLARE v_cols          INT DEFAULT 0;
    DECLARE v_dup_groups    INT DEFAULT 0;
    DECLARE v_dup_rows      INT DEFAULT 0;
    DECLARE v_null_active   INT DEFAULT 0;
    DECLARE v_index_exists  INT DEFAULT 0;
    DECLARE v_msg           VARCHAR(128);

    SET v_schema = DATABASE();

    -- ========== GUARD-0：M1 必须已执行 ==========
    SELECT COUNT(*) INTO v_cols
      FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = v_schema
       AND TABLE_NAME   = 'kb_document'
       AND COLUMN_NAME IN ('content_sha256', 'is_deleted');

    IF v_cols < 2 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'M2 ABORTED: M1 not applied (content_sha256/is_deleted missing). Run M1 first.';
    END IF;

    -- ========== 幂等短路：索引已存在 ==========
    SELECT COUNT(*) INTO v_index_exists
      FROM INFORMATION_SCHEMA.STATISTICS
     WHERE TABLE_SCHEMA = v_schema
       AND TABLE_NAME   = 'kb_document'
       AND INDEX_NAME   = 'uk_document_content_sha256_is_deleted'
       AND SEQ_IN_INDEX = 1;

    IF v_index_exists > 0 THEN
        SELECT 'SKIP: uk_document_content_sha256_is_deleted already exists' AS migration_note;
        LEAVE proc_label;
    END IF;

    -- ========== GUARD-1：执行前重复组检测（规格第9节，硬中止）==========
    SELECT COUNT(*), IFNULL(SUM(c), 0)
      INTO v_dup_groups, v_dup_rows
      FROM (SELECT content_sha256, COUNT(*) AS c
              FROM kb_document
             WHERE is_deleted = 0
               AND content_sha256 IS NOT NULL
             GROUP BY content_sha256
            HAVING COUNT(*) > 1) AS dup;

    IF v_dup_groups > 0 THEN
        SET v_msg = CONCAT('M2 ABORTED: ', v_dup_groups, ' active dup group(s)/', v_dup_rows,
                           ' row(s). Run PR-3 dedup first. No data modified.');
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = v_msg;
    END IF;

    -- ========== REPORT-2：未 backfill 的 active 行（仅报告，不中止）==========
    SELECT COUNT(*) INTO v_null_active
      FROM kb_document
     WHERE is_deleted = 0 AND content_sha256 IS NULL;

    IF v_null_active > 0 THEN
        SELECT CONCAT('WARN: ', v_null_active,
                      ' active row(s) still have NULL content_sha256; run backfill before governance')
               AS migration_note;
    END IF;

    -- ========== 建立唯一约束 ==========
    ALTER TABLE kb_document
        ADD UNIQUE INDEX uk_document_content_sha256_is_deleted (content_sha256, is_deleted);

    SELECT 'OK: uk_document_content_sha256_is_deleted created' AS migration_note;
END$$

DELIMITER ;

CALL sp_m2_add_unique_content_identity();

DROP PROCEDURE IF EXISTS sp_m2_add_unique_content_identity;

-- ------------------------------------------------------------
-- 执行后自检（只读）
-- ------------------------------------------------------------
SELECT INDEX_NAME, NON_UNIQUE, SEQ_IN_INDEX, COLUMN_NAME
  FROM INFORMATION_SCHEMA.STATISTICS
 WHERE TABLE_SCHEMA = DATABASE()
   AND TABLE_NAME   = 'kb_document'
   AND INDEX_NAME   = 'uk_document_content_sha256_is_deleted'
 ORDER BY SEQ_IN_INDEX;

-- 期望：2 行，NON_UNIQUE = 0，SEQ 1 = content_sha256，SEQ 2 = is_deleted
