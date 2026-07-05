#!/bin/bash
# ===========================================
# RAG 流程测试脚本
# 使用方式：
#   1. 先确保服务已启动: mvn spring-boot:run
#   2. source scripts/test_rag_flow.sh
# 前置条件：Spring Boot 已启动在 localhost:8080
# ===========================================

BASE_URL="http://localhost:8080"
SAMPLE_FILE="${SAMPLE_FILE:-samples/company_policy.txt}"

echo "========================================="
echo "RAG 流程测试"
echo "========================================="
echo ""

# 1. 上传文档
echo ">>> 1. 上传文档: $SAMPLE_FILE"
if [ ! -f "$SAMPLE_FILE" ]; then
    echo "错误: 样例文件不存在: $SAMPLE_FILE"
    echo "请设置 SAMPLE_FILE 环境变量指定文件路径"
    exit 1
fi

UPLOAD_RESPONSE=$(curl -s -X POST "$BASE_URL/api/documents/upload" \
    -F "file=@$SAMPLE_FILE")
echo "$UPLOAD_RESPONSE" | python3 -m json.tool 2>/dev/null || echo "$UPLOAD_RESPONSE"

# 提取文档 ID
DOCUMENT_ID=$(echo "$UPLOAD_RESPONSE" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['id'])" 2>/dev/null)
if [ -z "$DOCUMENT_ID" ]; then
    echo "错误: 无法获取文档 ID"
    exit 1
fi
echo "文档 ID: $DOCUMENT_ID"
echo ""

# 2. 解析文档
echo ">>> 2. 解析文档 (ID=$DOCUMENT_ID)"
curl -s -X POST "$BASE_URL/api/documents/$DOCUMENT_ID/parse" | python3 -m json.tool 2>/dev/null
echo ""

# 3. 向量化文档
echo ">>> 3. 向量化文档 (ID=$DOCUMENT_ID)"
curl -s -X POST "$BASE_URL/api/documents/$DOCUMENT_ID/index" | python3 -m json.tool 2>/dev/null
echo ""

# 4. 语义检索
echo ">>> 4. 语义检索"
curl -s -X POST "$BASE_URL/api/search" \
    -H "Content-Type: application/json" \
    -d '{"query": "公司报销制度是什么？", "topK": 5}' | python3 -m json.tool 2>/dev/null
echo ""

# 5. RAG 问答
echo ">>> 5. RAG 问答"
curl -s -X POST "$BASE_URL/api/rag/ask" \
    -H "Content-Type: application/json" \
    -d '{"question": "公司报销制度是什么？", "topK": 5}' | python3 -m json.tool 2>/dev/null
echo ""

echo "========================================="
echo "RAG 流程测试完成！"
echo "========================================="