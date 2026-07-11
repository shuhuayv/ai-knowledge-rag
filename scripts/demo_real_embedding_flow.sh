#!/bin/bash
# ===========================================================
# 真实 Embedding 流程演示脚本（智谱 embedding-3）
# 串联 upload -> parse/chunk -> index -> search -> ask
# 打印 references 与 score，验证真实语义检索。
# 使用方式：
#   1. 先确保服务已以真实模式启动（见 docs/REAL_EMBEDDING.md）：
#      export AI_EMBEDDING_PROVIDER=zhipu
#      export ZHIPU_API_KEY='<your-local-api-key>'
#      mvn spring-boot:run
#   2. bash scripts/demo_real_embedding_flow.sh
# 前置条件：Spring Boot 已启动在 BASE_URL
# 注意：本脚本不读取、不打印任何 API Key，仅从环境变量读取模式开关。
# ===========================================================
set -euo pipefail

# 以脚本所在目录为基准定位项目根（可被环境变量覆盖）
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BASE_URL="${BASE_URL:-http://localhost:8080}"
SAMPLE_FILE="${SAMPLE_FILE:-$SCRIPT_DIR/../samples/company_policy.txt}"

# 从环境变量读取 Embedding 模式（不做任何 Key 处理）
AI_EMBEDDING_PROVIDER="${AI_EMBEDDING_PROVIDER:-mock}"
AI_EMBEDDING_MODEL="${AI_EMBEDDING_MODEL:-embedding-3}"
AI_EMBEDDING_DIMENSIONS="${AI_EMBEDDING_DIMENSIONS:-1024}"
AI_EMBEDDING_FALLBACK_ENABLED="${AI_EMBEDDING_FALLBACK_ENABLED:-false}"

# ---------- JSON 解析工具 ----------
if command -v jq >/dev/null 2>&1; then
    JSON_PARSER="jq"
elif command -v python3 >/dev/null 2>&1; then
    JSON_PARSER="python3"
else
    echo "错误: 需要安装 jq 或 python3 来解析 JSON"
    exit 1
fi

json_field() {
    local field="$1"
    if [ "$JSON_PARSER" = "jq" ]; then
        jq -r ".$field // empty" 2>/dev/null
    else
        python3 -c "
import sys, json
d = json.load(sys.stdin)
cur = d
for part in '$field'.split('.'):
    if not isinstance(cur, dict) or part not in cur:
        cur = None
        break
    cur = cur[part]
print(cur if cur is not None else '')
" 2>/dev/null
    fi
}

self_check() {
    local sample='{"data":{"id":"abc"}}'
    local got
    got=$(echo "$sample" | json_field "data.id")
    if [ "$got" != "abc" ]; then
        echo "错误: JSON 解析自检失败（期望 data.id=abc，实际=$got）"
        exit 1
    fi
    echo "[OK] JSON 解析自检通过（支持嵌套路径 data.id）"
}

json_pretty() {
    if [ "$JSON_PARSER" = "jq" ]; then
        jq '.' 2>/dev/null || cat
    else
        python3 -m json.tool 2>/dev/null || cat
    fi
}

# JSON 解析工具定义完成后执行一次自检（支持嵌套路径 data.id）
self_check

check_response() {
    local step_name="$1" response="$2" http_code="$3"
    if [ "$http_code" != "200" ]; then
        echo "错误: [$step_name] HTTP 状态码异常: $http_code"
        echo "$response" | json_pretty
        exit 1
    fi
    local code
    code=$(echo "$response" | json_field "code")
    if [ "$code" != "0" ]; then
        echo "错误: [$step_name] 业务返回失败 (code=$code)"
        echo "$response" | json_pretty
        exit 1
    fi
}

echo "============================================================"
echo "  真实 Embedding 流程演示"
echo "============================================================"
echo ""
echo "  Embedding 模式: provider=$AI_EMBEDDING_PROVIDER"
echo "  model=$AI_EMBEDDING_MODEL  dimensions=$AI_EMBEDDING_DIMENSIONS"
echo "  fallbackEnabled=$AI_EMBEDDING_FALLBACK_ENABLED"
if [ "$AI_EMBEDDING_PROVIDER" = "zhipu" ] && [ -z "${ZHIPU_API_KEY:-}${AI_API_KEY:-}" ]; then
    echo "  [警告] provider=zhipu 但未检测到 ZHIPU_API_KEY / AI_API_KEY，索引将失败（默认 fallback=false）"
fi
echo "  BASE_URL=$BASE_URL"
echo "  测试文件: $SAMPLE_FILE"
echo "============================================================"
echo ""

# ---------- 状态预检（真实模式必需）----------
echo "============================================================"
echo "  状态预检: GET $BASE_URL/api/embedding/status"
echo "============================================================"
STATUS_RESPONSE=$(curl -s -w "\n%{http_code}" "$BASE_URL/api/embedding/status")
STATUS_HTTP_CODE=$(echo "$STATUS_RESPONSE" | tail -1)
STATUS_BODY=$(echo "$STATUS_RESPONSE" | sed '$d')
if [ "$STATUS_HTTP_CODE" != "200" ]; then
    echo "错误: 状态接口返回异常 HTTP 状态码: $STATUS_HTTP_CODE"
    echo "$STATUS_BODY" | json_pretty
    exit 1
fi

STATUS_PROVIDER=$(echo "$STATUS_BODY" | json_field "data.provider")
STATUS_MODE=$(echo "$STATUS_BODY" | json_field "data.mode")
STATUS_MODEL=$(echo "$STATUS_BODY" | json_field "data.model")
STATUS_DIMENSIONS=$(echo "$STATUS_BODY" | json_field "data.dimensions")
STATUS_APIKEY=$(echo "$STATUS_BODY" | json_field "data.apiKeyConfigured")

if [ "$STATUS_PROVIDER" != "zhipu" ] || [ "$STATUS_MODE" != "REAL" ] \
   || [ "$STATUS_MODEL" != "embedding-3" ] || [ "$STATUS_DIMENSIONS" != "1024" ] \
   || [ "$STATUS_APIKEY" != "true" ]; then
    echo "错误: Embedding 状态预检未通过（真实模式必需）"
    echo "  实际取值: provider=$STATUS_PROVIDER mode=$STATUS_MODE model=$STATUS_MODEL dimensions=$STATUS_DIMENSIONS apiKeyConfigured=$STATUS_APIKEY"
    echo "  期望: provider=zhipu mode=REAL model=embedding-3 dimensions=1024 apiKeyConfigured=true"
    exit 1
fi

echo "  [OK] 状态预检通过（provider=${STATUS_PROVIDER}, mode=${STATUS_MODE}, model=${STATUS_MODEL}, dimensions=${STATUS_DIMENSIONS}, apiKeyConfigured=${STATUS_APIKEY}）"
echo ""

# Step 1: 上传
echo "============================================================"
echo "  Step 1/5: 上传文档"
echo "============================================================"
if [ ! -f "$SAMPLE_FILE" ]; then
    echo "错误: 样例文件不存在: $SAMPLE_FILE"
    exit 1
fi
UPLOAD_RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/documents/upload" -F "file=@$SAMPLE_FILE")
UPLOAD_HTTP_CODE=$(echo "$UPLOAD_RESPONSE" | tail -1)
UPLOAD_BODY=$(echo "$UPLOAD_RESPONSE" | sed '$d')
check_response "上传文档" "$UPLOAD_BODY" "$UPLOAD_HTTP_CODE"
DOCUMENT_ID=$(echo "$UPLOAD_BODY" | json_field "data.id")
echo "  documentId: $DOCUMENT_ID"
echo "  [OK] 文档上传成功"
echo ""

# Step 2: 解析
echo "============================================================"
echo "  Step 2/5: 解析文档（Chunk 切分）"
echo "============================================================"
PARSE_RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/documents/$DOCUMENT_ID/parse")
PARSE_HTTP_CODE=$(echo "$PARSE_RESPONSE" | tail -1)
PARSE_BODY=$(echo "$PARSE_RESPONSE" | sed '$d')
check_response "解析文档" "$PARSE_BODY" "$PARSE_HTTP_CODE"
CHUNK_COUNT=$(echo "$PARSE_BODY" | json_field "data.chunkCount")
echo "  chunkCount: $CHUNK_COUNT"
echo "  [OK] 文档解析完成"
echo ""

# Step 3: 向量化（真实 Embedding 写入隔离 Collection）
echo "============================================================"
echo "  Step 3/5: 向量化文档（真实 Embedding -> Qdrant）"
echo "============================================================"
INDEX_RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/documents/$DOCUMENT_ID/index")
INDEX_HTTP_CODE=$(echo "$INDEX_RESPONSE" | tail -1)
INDEX_BODY=$(echo "$INDEX_RESPONSE" | sed '$d')
check_response "向量化文档" "$INDEX_BODY" "$INDEX_HTTP_CODE"
VECTOR_COUNT=$(echo "$INDEX_BODY" | json_field "data.vectorCount")
COLLECTION_NAME=$(echo "$INDEX_BODY" | json_field "data.collectionName")
echo "  vectorCount   : $VECTOR_COUNT"
echo "  collectionName: $COLLECTION_NAME"
echo "  [OK] 向量化完成（provider=$AI_EMBEDDING_PROVIDER, dimensions=$AI_EMBEDDING_DIMENSIONS）"
echo ""

# Step 4: 语义检索（真实相似度）
echo "============================================================"
echo "  Step 4/5: 语义检索"
echo "============================================================"
QUERY="公司报销制度是什么？"
SEARCH_RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/search" \
    -H "Content-Type: application/json" \
    -d "{\"query\": \"$QUERY\", \"topK\": 5}")
SEARCH_HTTP_CODE=$(echo "$SEARCH_RESPONSE" | tail -1)
SEARCH_BODY=$(echo "$SEARCH_RESPONSE" | sed '$d')
check_response "语义检索" "$SEARCH_BODY" "$SEARCH_HTTP_CODE"
echo "  查询: $QUERY"
echo "  检索结果与 score:"
echo "$SEARCH_BODY" | json_pretty
echo "  [OK] 语义检索完成"
echo ""

# Step 5: RAG 问答
echo "============================================================"
echo "  Step 5/5: RAG 问答"
echo "============================================================"
QUESTION="公司报销制度是什么？"
ASK_RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/rag/ask" \
    -H "Content-Type: application/json" \
    -d "{\"question\": \"$QUESTION\", \"topK\": 5}")
ASK_HTTP_CODE=$(echo "$ASK_RESPONSE" | tail -1)
ASK_BODY=$(echo "$ASK_RESPONSE" | sed '$d')
check_response "RAG 问答" "$ASK_BODY" "$ASK_HTTP_CODE"
echo "  问题: $QUESTION"
echo "  answer:"
echo "$ASK_BODY" | json_field "data.answer"
echo ""
echo "  references (含 score):"
echo "$ASK_BODY" | json_field "data.references" | json_pretty
echo ""
echo "  检索透明性字段:"
echo "$ASK_BODY" | json_field "data.embeddingProvider" | sed 's/^/    embeddingProvider: /'
echo "$ASK_BODY" | json_field "data.embeddingMode" | sed 's/^/    embeddingMode: /'
echo "$ASK_BODY" | json_field "data.collectionName" | sed 's/^/    collectionName: /'
echo "$ASK_BODY" | json_field "data.retrievalCandidateCount" | sed 's/^/    retrievalCandidateCount: /'
echo "$ASK_BODY" | json_field "data.retrievalReturnedCount" | sed 's/^/    retrievalReturnedCount: /'
echo ""
echo "============================================================"
echo "  演示完成"
echo "============================================================"
