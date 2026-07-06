#!/bin/bash
# ===========================================
# RAG 流程演示脚本
# 串联 upload -> parse/chunk -> index -> search -> chat
# 使用方式：
#   1. 先确保服务已启动: mvn spring-boot:run
#   2. bash scripts/demo_rag_flow.sh
# 前置条件：Spring Boot 已启动在 localhost:8080
# 注意：当前使用 Mock Embedding / Mock AI，不需要真实 API Key
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

json_field() {
    local field="$1"
    if [ "$JSON_PARSER" = "jq" ]; then
        jq -r ".$field // empty" 2>/dev/null
    else
        python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('$field',''))" 2>/dev/null
    fi
}

json_pretty() {
    if [ "$JSON_PARSER" = "jq" ]; then
        jq '.' 2>/dev/null || cat
    else
        python3 -m json.tool 2>/dev/null || cat
    fi
}

check_response() {
    local step_name="$1"
    local response="$2"
    local http_code="$3"

    if [ "$http_code" != "200" ]; then
        echo ""
        echo "========================================="
        echo "错误: [$step_name] HTTP 状态码异常: $http_code"
        echo "========================================="
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
        echo "$response" | json_pretty
        exit 1
    fi
}

echo "============================================================"
echo "  RAG 知识库流程演示"
echo "============================================================"
echo ""
echo "  注意: 当前使用 Mock Embedding / Mock AI"
echo "  不需要配置真实 API Key"
echo ""
echo "  BASE_URL: $BASE_URL"
echo "  测试文件: $SAMPLE_FILE"
echo "============================================================"
echo ""

# ============================================================
# Step 1: 上传文档
# ============================================================
echo "============================================================"
echo "  Step 1/5: 上传文档"
echo "============================================================"
echo ""

if [ ! -f "$SAMPLE_FILE" ]; then
    echo "错误: 样例文件不存在: $SAMPLE_FILE"
    exit 1
fi

FILE_SIZE=$(wc -c < "$SAMPLE_FILE" | tr -d ' ')
echo "文件: $SAMPLE_FILE (${FILE_SIZE} bytes)"
echo ""

UPLOAD_RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/documents/upload" \
    -F "file=@$SAMPLE_FILE")
UPLOAD_HTTP_CODE=$(echo "$UPLOAD_RESPONSE" | tail -1)
UPLOAD_BODY=$(echo "$UPLOAD_RESPONSE" | sed '$d')

check_response "上传文档" "$UPLOAD_BODY" "$UPLOAD_HTTP_CODE"

DOCUMENT_ID=$(echo "$UPLOAD_BODY" | json_field "data.id")
FILE_NAME=$(echo "$UPLOAD_BODY" | json_field "data.fileName")
FILE_TYPE=$(echo "$UPLOAD_BODY" | json_field "data.fileType")
STATUS=$(echo "$UPLOAD_BODY" | json_field "data.status")

echo "  documentId  : $DOCUMENT_ID"
echo "  fileName    : $FILE_NAME"
echo "  fileType    : $FILE_TYPE"
echo "  status      : $STATUS"
echo ""
echo "  [OK] 文档上传成功"
echo ""

# ============================================================
# Step 2: 解析文档（Chunk 切分）
# ============================================================
echo "============================================================"
echo "  Step 2/5: 解析文档（Chunk 切分）"
echo "============================================================"
echo ""

PARSE_RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/documents/$DOCUMENT_ID/parse")
PARSE_HTTP_CODE=$(echo "$PARSE_RESPONSE" | tail -1)
PARSE_BODY=$(echo "$PARSE_RESPONSE" | sed '$d')

check_response "解析文档" "$PARSE_BODY" "$PARSE_HTTP_CODE"

CHUNK_COUNT=$(echo "$PARSE_BODY" | json_field "data.chunkCount")
echo "  chunkCount  : $CHUNK_COUNT"
echo ""
echo "  [OK] 文档解析（Chunk 切分）完成"
echo ""

# ============================================================
# Step 3: 向量化文档
# ============================================================
echo "============================================================"
echo "  Step 3/5: 向量化文档（写入 Qdrant）"
echo "============================================================"
echo ""

INDEX_RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/documents/$DOCUMENT_ID/index")
INDEX_HTTP_CODE=$(echo "$INDEX_RESPONSE" | tail -1)
INDEX_BODY=$(echo "$INDEX_RESPONSE" | sed '$d')

check_response "向量化文档" "$INDEX_BODY" "$INDEX_HTTP_CODE"

VECTOR_COUNT=$(echo "$INDEX_BODY" | json_field "data.vectorCount")
echo "  vectorCount : $VECTOR_COUNT"
echo ""
echo "  [OK] 向量化完成（Mock Embedding, dimension=384）"
echo ""

# ============================================================
# Step 4: 语义检索
# ============================================================
echo "============================================================"
echo "  Step 4/5: 语义检索"
echo "============================================================"
echo ""

QUERY="公司报销制度是什么？"
echo "  查询: $QUERY"
echo ""

SEARCH_RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/search" \
    -H "Content-Type: application/json" \
    -d "{\"query\": \"$QUERY\", \"topK\": 5}")
SEARCH_HTTP_CODE=$(echo "$SEARCH_RESPONSE" | tail -1)
SEARCH_BODY=$(echo "$SEARCH_RESPONSE" | sed '$d')

check_response "语义检索" "$SEARCH_BODY" "$SEARCH_HTTP_CODE"

echo "  检索结果:"
echo "$SEARCH_BODY" | json_pretty
echo ""
echo "  [OK] 语义检索完成"
echo ""

# ============================================================
# Step 5: RAG 问答
# ============================================================
echo "============================================================"
echo "  Step 5/5: RAG 问答"
echo "============================================================"
echo ""

QUESTION="公司报销制度是什么？"
echo "  问题: $QUESTION"
echo ""

ASK_RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/rag/ask" \
    -H "Content-Type: application/json" \
    -d "{\"question\": \"$QUESTION\", \"topK\": 5}")
ASK_HTTP_CODE=$(echo "$ASK_RESPONSE" | tail -1)
ASK_BODY=$(echo "$ASK_RESPONSE" | sed '$d')

check_response "RAG 问答" "$ASK_BODY" "$ASK_HTTP_CODE"

ANSWER=$(echo "$ASK_BODY" | json_field "data.answer")
echo "  answer      : $ANSWER"
echo ""
echo "  references  :"
echo "$ASK_BODY" | json_field "data.references" | json_pretty
echo ""
echo "  [OK] RAG 问答完成（Mock AI）"
echo ""

# ============================================================
# 演示总结
# ============================================================
echo "============================================================"
echo "  演示总结"
echo "============================================================"
echo ""
echo "  documentId  : $DOCUMENT_ID"
echo "  chunkCount  : $CHUNK_COUNT"
echo "  vectorCount : $VECTOR_COUNT"
echo "  search      : 已检索相关文档片段"
echo "  answer      : $ANSWER"
echo "  references  : 已返回引用来源"
echo ""
echo "  注意: 当前使用 Mock Embedding / Mock AI"
echo "  向量为随机生成（dimension=384），仅供参考流程验证"
echo ""
echo "============================================================"
echo "  RAG 流程演示完成！"
echo "============================================================"