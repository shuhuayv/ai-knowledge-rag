#!/bin/bash
# ===========================================
# RAG 流程测试脚本
# 使用方式：
#   1. 先确保服务已启动: mvn spring-boot:run
#   2. bash scripts/test_rag_flow.sh
# 前置条件：Spring Boot 已启动在 localhost:8080
# ===========================================
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
SAMPLE_FILE="${SAMPLE_FILE:-samples/company_policy.txt}"

# 检查 JSON 解析工具
if command -v jq &>/dev/null; then
    JSON_PARSER="jq"
elif command -v python3 &>/dev/null; then
    JSON_PARSER="python3"
else
    echo "错误: 需要安装 jq 或 python3 来解析 JSON"
    echo "  macOS:  brew install jq"
    echo "  或确保 python3 可用"
    exit 1
fi

# JSON 格式化函数
json_pretty() {
    if [ "$JSON_PARSER" = "jq" ]; then
        jq '.' 2>/dev/null || cat
    else
        python3 -m json.tool 2>/dev/null || cat
    fi
}

# 提取 JSON 字段
json_field() {
    local field="$1"
    if [ "$JSON_PARSER" = "jq" ]; then
        jq -r ".$field // empty" 2>/dev/null
    else
        python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('$field',''))" 2>/dev/null
    fi
}

# 检查 API 响应是否成功（code=0 或 HTTP 200）
check_response() {
    local step_name="$1"
    local response="$2"
    local http_code="$3"

    if [ "$http_code" != "200" ]; then
        echo ""
        echo "========================================="
        echo "错误: [$step_name] HTTP 状态码异常: $http_code"
        echo "========================================="
        echo "响应内容:"
        echo "$response" | json_pretty
        exit 1
    fi

    local code
    code=$(echo "$response" | json_field "code")
    if [ "$code" != "0" ]; then
        echo ""
        echo "========================================="
        echo "错误: [$step_name] 业务返回失败 (code=$code)"
        echo "========================================="
        echo "响应内容:"
        echo "$response" | json_pretty
        exit 1
    fi
}

echo "========================================="
echo "RAG 流程测试"
echo "BASE_URL: $BASE_URL"
echo "========================================="
echo ""

# 1. 上传文档
echo ">>> 1. 上传文档: $SAMPLE_FILE"
if [ ! -f "$SAMPLE_FILE" ]; then
    echo "错误: 样例文件不存在: $SAMPLE_FILE"
    echo "请设置 SAMPLE_FILE 环境变量指定文件路径"
    exit 1
fi

UPLOAD_RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/documents/upload" \
    -F "file=@$SAMPLE_FILE")
UPLOAD_HTTP_CODE=$(echo "$UPLOAD_RESPONSE" | tail -1)
UPLOAD_BODY=$(echo "$UPLOAD_RESPONSE" | sed '$d')

check_response "上传文档" "$UPLOAD_BODY" "$UPLOAD_HTTP_CODE"
echo "$UPLOAD_BODY" | json_pretty

DOCUMENT_ID=$(echo "$UPLOAD_BODY" | json_field "data.id")
if [ -z "$DOCUMENT_ID" ] || [ "$DOCUMENT_ID" = "null" ]; then
    echo "错误: 无法获取文档 ID"
    echo "响应内容:"
    echo "$UPLOAD_BODY" | json_pretty
    exit 1
fi
echo "文档 ID: $DOCUMENT_ID"
echo ""

# 2. 解析文档
echo ">>> 2. 解析文档 (ID=$DOCUMENT_ID)"
PARSE_RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/documents/$DOCUMENT_ID/parse")
PARSE_HTTP_CODE=$(echo "$PARSE_RESPONSE" | tail -1)
PARSE_BODY=$(echo "$PARSE_RESPONSE" | sed '$d')

check_response "解析文档" "$PARSE_BODY" "$PARSE_HTTP_CODE"
echo "$PARSE_BODY" | json_pretty
echo ""

# 3. 向量化文档
echo ">>> 3. 向量化文档 (ID=$DOCUMENT_ID)"
INDEX_RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/documents/$DOCUMENT_ID/index")
INDEX_HTTP_CODE=$(echo "$INDEX_RESPONSE" | tail -1)
INDEX_BODY=$(echo "$INDEX_RESPONSE" | sed '$d')

check_response "向量化文档" "$INDEX_BODY" "$INDEX_HTTP_CODE"
echo "$INDEX_BODY" | json_pretty
echo ""

# 4. 语义检索
echo ">>> 4. 语义检索"
SEARCH_RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/search" \
    -H "Content-Type: application/json" \
    -d '{"query": "公司报销制度是什么？", "topK": 5}')
SEARCH_HTTP_CODE=$(echo "$SEARCH_RESPONSE" | tail -1)
SEARCH_BODY=$(echo "$SEARCH_RESPONSE" | sed '$d')

check_response "语义检索" "$SEARCH_BODY" "$SEARCH_HTTP_CODE"
echo "$SEARCH_BODY" | json_pretty
echo ""

# 5. RAG 问答
echo ">>> 5. RAG 问答"
ASK_RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/rag/ask" \
    -H "Content-Type: application/json" \
    -d '{"question": "公司报销制度是什么？", "topK": 5}')
ASK_HTTP_CODE=$(echo "$ASK_RESPONSE" | tail -1)
ASK_BODY=$(echo "$ASK_RESPONSE" | sed '$d')

check_response "RAG 问答" "$ASK_BODY" "$ASK_HTTP_CODE"
echo "$ASK_BODY" | json_pretty
echo ""

echo "========================================="
echo "RAG 流程测试完成！"
echo "========================================="