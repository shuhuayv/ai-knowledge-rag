#!/bin/bash
# ===========================================
# 初始化 ai_knowledge_rag 数据库
# 使用方式：source scripts/init_db.sh
# 前置条件：MySQL 已启动，已设置环境变量
# ===========================================

set -e

# 环境变量默认值
DB_NAME="${DB_NAME:-ai_knowledge_rag}"
DB_USERNAME="${DB_USERNAME:-ai_dev}"
DB_PASSWORD="${DB_PASSWORD:-}"

# SQL 文件路径
SQL_FILE="$(dirname "$0")/../sql/init.sql"

if [ ! -f "$SQL_FILE" ]; then
    echo "错误: SQL 文件不存在: $SQL_FILE"
    exit 1
fi

echo "========================================="
echo "初始化数据库: $DB_NAME"
echo "用户: $DB_USERNAME"
echo "========================================="

# 创建数据库（如果不存在）
mysql -u"$DB_USERNAME" -p"$DB_PASSWORD" -e "CREATE DATABASE IF NOT EXISTS $DB_NAME DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;" 2>/dev/null

# 执行建表脚本
mysql -u"$DB_USERNAME" -p"$DB_PASSWORD" "$DB_NAME" < "$SQL_FILE" 2>/dev/null

echo "数据库初始化完成: $DB_NAME"
echo "已执行: $SQL_FILE"