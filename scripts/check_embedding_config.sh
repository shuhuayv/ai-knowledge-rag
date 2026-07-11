#!/bin/bash
# ===========================================================
# Embedding 配置预检脚本
# 检查 MySQL / Redis / Qdrant 可达，并查询 RAG backend 的
# GET /api/embedding/status 接口。
# 仅输出 apiKeyConfigured 的 true/false，绝不打印任何 API Key。
# 前置条件：Spring Boot 已启动在 BASE_URL（默认 http://localhost:8080）
# ===========================================================
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
STATUS_FILE="$(mktemp -t embedding_status.XXXXXX.json)"

# ---------- 依赖 TCP 探活 ----------
check_tcp() {
    local host="$1" port="$2" name="$3"
    if command -v nc >/dev/null 2>&1; then
        if nc -z -w 3 "$host" "$port" 2>/dev/null; then
            echo "  [OK]   $name 可达 ($host:$port)"
            return 0
        fi
    else
        # 回退：Bash /dev/tcp
        if (exec 3<>"/dev/tcp/$host/$port") 2>/dev/null; then
            exec 3>&- 2>/dev/null || true
            echo "  [OK]   $name 可达 ($host:$port)"
            return 0
        fi
    fi
    echo "  [FAIL] $name 不可达 ($host:$port)"
    return 1
}

echo "============================================================"
echo "  Embedding 配置预检"
echo "============================================================"
echo ""
echo "  依赖探活:"
check_tcp "${DB_HOST:-localhost}" "${DB_PORT:-3306}" "MySQL"
check_tcp "${REDIS_HOST:-localhost}" "${REDIS_PORT:-6379}" "Redis"
check_tcp "${QDRANT_HOST:-localhost}" "${QDRANT_PORT:-6333}" "Qdrant"

echo ""
echo "  RAG Backend 状态接口: GET $BASE_URL/api/embedding/status"

HTTP_CODE=$(curl -s -o "$STATUS_FILE" -w "%{http_code}" "$BASE_URL/api/embedding/status" || echo "000")
if [ "$HTTP_CODE" != "200" ]; then
    echo "  [FAIL] 状态接口返回 HTTP $HTTP_CODE"
    rm -f "$STATUS_FILE"
    exit 1
fi

# 仅提取非敏感字段；绝不打印 Key
if command -v python3 >/dev/null 2>&1; then
    python3 - "$STATUS_FILE" <<'PY'
import sys, json
try:
    d = json.load(open(sys.argv[1]))["data"]
except Exception as e:
    print("  [FAIL] 无法解析状态接口响应:", e)
    sys.exit(1)
print("  provider          :", d.get("provider"))
print("  model             :", d.get("model"))
print("  dimensions        :", d.get("dimensions"))
print("  mode              :", d.get("mode"))
print("  collectionName    :", d.get("collectionName"))
print("  fallbackEnabled   :", d.get("fallbackEnabled"))
print("  apiKeyConfigured  :", d.get("apiKeyConfigured"))
PY
    RC=$?
else
    # 无 python3 时，仅用 grep 提取 apiKeyConfigured 布尔值
    API_KEY_CONFIGURED=$(grep -o '"apiKeyConfigured"[ ]*:[ ]*\(true\|false\)' "$STATUS_FILE" | grep -o '\(true\|false\)' || echo "unknown")
    echo "  apiKeyConfigured  : $API_KEY_CONFIGURED"
    RC=0
fi

rm -f "$STATUS_FILE"

if [ "${RC:-0}" -ne 0 ]; then
    echo "  [FAIL] 状态接口解析失败"
    exit 1
fi

echo ""
echo "  [OK] 配置检查通过（未输出任何 API Key）"
echo "============================================================"
