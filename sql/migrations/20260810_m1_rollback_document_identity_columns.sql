-- ============================================================
-- M1 ROLLBACK：删除 kb_document 的文档身份列
-- 日期：2026-08-10
-- 关联：RAG PR-2A / feature/content-dedup
--
-- 🔴 NOT EXECUTED IN THIS ROUND —— 本轮仅提交文件与离线审查，禁止真实执行。
--    REAL_DB_MIGRATION_ALLOWED = NO
--
-- ⚠️⚠️ 适用范围（规格第10节，必须写清）：
--    本脚本**只适用于 governance（去重治理）尚未开始的阶段**。
--    一旦 is_deleted / canonical_document_id / dedup_batch 中任何一个已被写入真实治理数据，
--    DROP COLUMN 将永久丢失审计信息 → 本脚本会主动中止，禁止继续。
--    此时正确做法不是回滚 M1，而是走前向修复（forward fix）。
--
-- 【守卫】
--    GUARD-A：M2 unique index 仍存在      → 中止（必须先回滚 M2）
--    GUARD-B：治理数据已产生               → 中止（is_deleted<>0 / canonical_document_id NOT NULL / dedup_batch NOT NULL）
--    GUARD-C：M1 部分应用（4 列不齐全）     → 中止（拒绝在不确定状态下猜测，需人工介入）
--    SKIP   ：4 列全不存在                 → 打印 SKIP 正常退出（幂等，无事可做）
--
-- 【破坏性】YES（这是 rollback，本就要 DROP 4 列）。因此三重守卫是本文件的核心。
--          守卫命中时数据库状态零变化。
--
-- 【中止机制】SIGNAL SQLSTATE '45000'（只能写在存储程序内，故使用临时存储过程）。
--          mysql CLI 在不加 --force 时遇错立即以非 0 退出码中断，DROP 绝不会被执行。
--
-- 【执行】 mysql -u ai_dev -p ai_knowledge_rag < sql/migrations/20260810_m1_rollback_document_identity_columns.sql
--         必须用 mysql CLI（脚本使用 DELIMITER）；不要加 --force。
-- ============================================================

-- ------------------------------------------------------------
-- DRY-RUN 预检（只读，执行 rollback 前务必先跑）
-- ------------------------------------------------------------
-- SELECT
--   SUM(is_deleted <> 0)                        AS soft_deleted_rows,
--   SUM(canonical_document_id IS NOT NULL)      AS canonical_marked_rows,
--   SUM(dedup_batch IS NOT NULL)                AS dedup_batch_rows
-- FROM kb_document;
-- 期望全部为 0，否则禁止回滚 M1。

DROP PROCEDURE IF EXISTS sp_m1_rollback_document_identity;

DELIMITER $$

CREATE PROCEDURE sp_m1_rollback_document_identity()
proc_label: BEGIN
    DECLARE v_schema       VARCHAR(64);
    DECLARE v_cols         INT DEFAULT 0;
    DECLARE v_index_exists INT DEFAULT 0;
    DECLARE v_governance   INT DEFAULT 0;
    DECLARE v_msg          VARCHAR(128);

    SET v_schema = DATABASE();

    -- ========== 状态判定：4 个目标列存在几个 ==========
    SELECT COUNT(*) INTO v_cols
      FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = v_schema
       AND TABLE_NAME   = 'kb_document'
       AND COLUMN_NAME IN ('content_sha256','is_deleted','canonical_document_id','dedup_batch');

    -- 幂等：M1 从未执行（或已回滚）→ 无事可做
    IF v_cols = 0 THEN
        SELECT 'SKIP: M1 columns not present, nothing to roll back' AS migration_note;
        LEAVE proc_label;
    END IF;

    -- ========== GUARD-C：部分应用状态 → 拒绝猜测 ==========
    IF v_cols < 4 THEN
        SET v_msg = CONCAT('M1 ROLLBACK ABORTED: partial M1 state (', v_cols,
                           '/4 columns). Manual inspection required.');
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = v_msg;
    END IF;

    -- ========== GUARD-A：M2 unique index 必须先回滚 ==========
    SELECT COUNT(*) INTO v_index_exists
      FROM INFORMATION_SCHEMA.STATISTICS
     WHERE TABLE_SCHEMA = v_schema
       AND TABLE_NAME   = 'kb_document'
       AND INDEX_NAME   = 'uk_document_content_sha256_is_deleted'
       AND SEQ_IN_INDEX = 1;

    IF v_index_exists > 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'M1 ROLLBACK ABORTED: M2 unique index still exists. Roll back M2 first.';
    END IF;

    -- ========== GUARD-B：治理数据保护（核心守卫）==========
    SELECT COUNT(*) INTO v_governance
      FROM kb_document
     WHERE is_deleted <> 0
        OR canonical_document_id IS NOT NULL
        OR dedup_batch IS NOT NULL;

    IF v_governance > 0 THEN
        SET v_msg = CONCAT('M1 ROLLBACK ABORTED: ', v_governance,
                           ' row(s) carry governance data. DROP would lose audit trail.');
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = v_msg;
    END IF;

    -- ========== 全部守卫通过：执行回滚 ==========
    ALTER TABLE kb_document
        DROP COLUMN dedup_batch,
        DROP COLUMN canonical_document_id,
        DROP COLUMN is_deleted,
        DROP COLUMN content_sha256;

    SELECT 'OK: M1 columns dropped (governance had not started)' AS migration_note;
END$$

DELIMITER ;

CALL sp_m1_rollback_document_identity();

DROP PROCEDURE IF EXISTS sp_m1_rollback_document_identity;

-- ------------------------------------------------------------
-- 执行后自检（只读）：期望返回 0 行
-- ------------------------------------------------------------
SELECT COLUMN_NAME
  FROM INFORMATION_SCHEMA.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE()
   AND TABLE_NAME   = 'kb_document'
   AND COLUMN_NAME IN ('content_sha256','is_deleted','canonical_document_id','dedup_batch');
