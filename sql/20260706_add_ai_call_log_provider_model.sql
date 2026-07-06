-- 迁移脚本：ai_call_log 表增加 provider、model、error_message 字段
-- 日期：2026-07-06
-- 说明：接入真实 Chat API 后，ai_call_log 需要记录 provider 和 model 信息
-- 使用方式：
--   mysql -u ai_dev -p ai_knowledge_rag < sql/20260706_add_ai_call_log_provider_model.sql

ALTER TABLE ai_call_log
  ADD COLUMN provider VARCHAR(64) NULL COMMENT 'AI provider' AFTER api_type,
  ADD COLUMN model VARCHAR(128) NULL COMMENT 'AI model' AFTER provider,
  ADD COLUMN error_message TEXT COMMENT '错误信息' AFTER response_summary;