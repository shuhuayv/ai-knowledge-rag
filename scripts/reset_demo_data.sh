#!/bin/bash
# ===========================================
# Reset RAG Demo Data
# 清空 RAG demo 运行产生的 MySQL、Qdrant、uploads 数据
# 使用方式：
#   bash scripts/reset_demo_data.sh
# 注意：仅用于本地 demo 环境，不建议在生产环境执行
# ===========================================
set -euo pipefail

# 项目根目录识别
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

DB_NAME="${DB_NAME:-ai_knowledge_rag}"
DB_USERNAME="${DB_USERNAME:-ai_dev}"
DB_PASSWORD="${DB_PASSWORD:-}"
QDRANT_URL="${QDRANT_URL:-http://localhost:6333}"
QDRANT_COLLECTION="${QDRANT_COLLECTION:-kb_chunks}"
UPLOAD_DIR="${UPLOAD_DIR:-${PROJECT_ROOT}/uploads}"

# 计算 uploads 绝对路径，用于安全检查
UPLOAD_DIR_ABS="$(cd "$(dirname "${UPLOAD_DIR}")" 2>/dev/null && pwd)/$(basename "${UPLOAD_DIR}")" || UPLOAD_DIR_ABS=""
EXPECTED_UPLOADS="${PROJECT_ROOT}/uploads"

echo "============================================================"
echo "  Reset RAG Demo Data"
echo "============================================================"
echo ""
echo "  DB_NAME          : ${DB_NAME}"
echo "  DB_USERNAME      : ${DB_USERNAME}"
echo "  QDRANT_URL       : ${QDRANT_URL}"
echo "  QDRANT_COLLECTION: ${QDRANT_COLLECTION}"
echo "  UPLOAD_DIR       : ${UPLOAD_DIR_ABS}"
echo "============================================================"
echo ""

# ============================================================
# MySQL 函数封装
# ============================================================

# 执行 MySQL 命令，不打印 DB_PASSWORD
run_mysql() {
    local sql="$1"
    if [ -n "${DB_PASSWORD}" ]; then
        MYSQL_PWD="${DB_PASSWORD}" mysql -u "${DB_USERNAME}" "${DB_NAME}" -e "${sql}"
    else
        mysql -u "${DB_USERNAME}" "${DB_NAME}" -e "${sql}"
    fi
}

# 测试 MySQL 连接
test_mysql_connection() {
    if [ -n "${DB_PASSWORD}" ]; then
        MYSQL_PWD="${DB_PASSWORD}" mysql -u "${DB_USERNAME}" "${DB_NAME}" -e "SELECT 1;" > /dev/null 2>&1
    else
        mysql -u "${DB_USERNAME}" "${DB_NAME}" -e "SELECT 1;" > /dev/null 2>&1
    fi
}

# ============================================================
# MySQL 清理
# ============================================================
echo ">>> 1. Cleaning MySQL tables..."

if ! test_mysql_connection; then
    echo "错误: 无法连接 MySQL (${DB_NAME})，请检查数据库配置"
    exit 1
fi

# 先尝试 TRUNCATE
TRUNCATE_SQL="SET FOREIGN_KEY_CHECKS = 0;
TRUNCATE TABLE ai_call_log;
TRUNCATE TABLE kb_vector_record;
TRUNCATE TABLE kb_chunk;
TRUNCATE TABLE kb_document;
SET FOREIGN_KEY_CHECKS = 1;"

if run_mysql "${TRUNCATE_SQL}" > /dev/null 2>&1; then
    echo "  MySQL demo data reset completed (TRUNCATE)"
else
    # TRUNCATE 失败，回退到 DELETE
    echo "  TRUNCATE failed, falling back to DELETE..."
    DELETE_SQL="SET FOREIGN_KEY_CHECKS = 0;
DELETE FROM ai_call_log;
ALTER TABLE ai_call_log AUTO_INCREMENT = 1;
DELETE FROM kb_vector_record;
ALTER TABLE kb_vector_record AUTO_INCREMENT = 1;
DELETE FROM kb_chunk;
ALTER TABLE kb_chunk AUTO_INCREMENT = 1;
DELETE FROM kb_document;
ALTER TABLE kb_document AUTO_INCREMENT = 1;
SET FOREIGN_KEY_CHECKS = 1;"

    if run_mysql "${DELETE_SQL}" > /dev/null 2>&1; then
        echo "  MySQL demo data reset completed (DELETE)"
    else
        echo "错误: MySQL 清理失败"
        exit 1
    fi
fi

echo ""

# ============================================================
# Uploads 清理
# ============================================================
echo ">>> 2. Cleaning uploads directory..."

# 安全检查：UPLOAD_DIR_ABS 不能为空
if [ -z "${UPLOAD_DIR_ABS}" ]; then
    echo "错误: 无法解析 UPLOAD_DIR 绝对路径，拒绝清理"
    exit 1
fi

# 安全检查：UPLOAD_DIR_ABS 必须等于或位于项目 uploads 目录下
if [ "${UPLOAD_DIR_ABS}" != "${EXPECTED_UPLOADS}" ]; then
    # 检查是否在 EXPECTED_UPLOADS 子目录下
    case "${UPLOAD_DIR_ABS}" in
        "${EXPECTED_UPLOADS}/"*)
            ;;
        *)
            echo "错误: UPLOAD_DIR 不在项目目录下，拒绝清理"
            echo "  UPLOAD_DIR_ABS  : ${UPLOAD_DIR_ABS}"
            echo "  EXPECTED_UPLOADS: ${EXPECTED_UPLOADS}"
            exit 1
            ;;
    esac
fi

# 确保 uploads 目录存在
mkdir -p "${UPLOAD_DIR_ABS}"

# 删除 uploads 目录下的普通文件，不删除目录本身
if [ -d "${UPLOAD_DIR_ABS}" ]; then
    find "${UPLOAD_DIR_ABS}" -maxdepth 1 -type f -delete 2>/dev/null || true
    echo "  uploads cleaned"
else
    echo "  uploads directory not found, created"
fi
echo ""

# ============================================================
# Qdrant 清理
# ============================================================
echo ">>> 3. Cleaning Qdrant collection..."

# 检查 Qdrant 是否可访问
HTTP_CODE="000"
if ! HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" "${QDRANT_URL}/collections" 2>/dev/null); then
    HTTP_CODE="000"
fi

if [ "${HTTP_CODE}" = "000" ]; then
    echo "  Qdrant unavailable (${QDRANT_URL}), skipped"
    echo ""
    echo "============================================================"
    echo "  Reset completed (Qdrant skipped)"
    echo "============================================================"
    exit 0
fi

# 删除 collection
DELETE_CODE="000"
if ! DELETE_CODE=$(curl -s -o /dev/null -w "%{http_code}" -X DELETE "${QDRANT_URL}/collections/${QDRANT_COLLECTION}" 2>/dev/null); then
    DELETE_CODE="000"
fi

case "${DELETE_CODE}" in
    200)
        echo "  Qdrant collection deleted"
        ;;
    404)
        echo "  Qdrant collection not found, skipped"
        ;;
    *)
        echo "  Qdrant delete returned HTTP ${DELETE_CODE}, skipped"
        ;;
esac
echo ""

echo "============================================================"
echo "  Reset completed"
echo "============================================================"
