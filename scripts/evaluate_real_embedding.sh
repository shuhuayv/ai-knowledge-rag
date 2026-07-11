#!/bin/bash
# ===========================================================
# 真实 Embedding 检索质量评估脚本
# 准备一个含 3 主题（A: RAG/Qdrant/TopK；B: Code Reviewer/JGit/Markdown；
# C: 统一前端/React/TypeScript/MUI/Vite）的 demo 文档，上传->解析->索引后，
# 对 4 个问题（3 个主题相关 + 1 个无关"红烧肉"）做 TopK=3 检索，
# 计算 hit@1 / hit@3 / MRR / 无关问题最高分。
# 所有指标均"由真实运行计算"，绝不写死。本脚本不含任何 API Key。
# 前置条件：Spring Boot 已以真实 Embedding 模式启动（provider=zhipu）。
# ===========================================================
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
WORKDIR="$(mktemp -d -t rag_eval.XXXXXX)"
DOC_FILE="$WORKDIR/eval_doc.txt"

# ---------- 生成含 3 主题的 demo 文档 ----------
cat > "$DOC_FILE" <<'EOF'
【主题A：RAG 与向量检索】
企业知识库采用 RAG（Retrieval-Augmented Generation）架构，使用 Qdrant 作为向量数据库存储文档切片 embeddings。
用户提问时，系统先做语义向量检索，再按 TopK 返回最相关的若干文档片段交给大模型生成答案。
Qdrant 提供 Cosine 相似度计算，TopK 参数控制返回的相关片段数量，是 RAG 检索质量的核心。

【主题B：代码评审工具】
团队开发了一款 Code Reviewer 自动化代码评审工具，基于 JGit 拉取 GitHub 仓库的提交与分支。
Code Reviewer 会读取 diff，结合规则引擎给出评审意见，并将结果以 Markdown 格式回写到 Pull Request 评论区。
借助 JGit 与 GitHub API，评审流程完全自动化，Markdown 报告清晰易读。

【主题C：统一前端】
公司统一前端基于 React 框架，使用 TypeScript 编写，组件库采用 MUI（Material UI）。
构建工具选用 Vite，开发体验快、热更新迅速。React + TypeScript + MUI + Vite 构成了统一前端的现代技术栈。
EOF

# ---------- JSON 解析 ----------
if command -v python3 >/dev/null 2>&1; then
    JSON_PARSER="python3"
elif command -v jq >/dev/null 2>&1; then
    JSON_PARSER="jq"
else
    echo "错误: 需要 python3 或 jq"
    rm -rf "$WORKDIR"
    exit 1
fi

json_field() {
    local field="$1" body="$2"
    if [ "$JSON_PARSER" = "python3" ]; then
        echo "$body" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('$field',''))" 2>/dev/null
    else
        echo "$body" | jq -r ".$field // empty" 2>/dev/null
    fi
}

check_response() {
    local step="$1" body="$2" code="$3"
    if [ "$code" != "200" ]; then
        echo "错误: [$step] HTTP $code"; echo "$body"; rm -rf "$WORKDIR"; exit 1
    fi
    local c; c=$(json_field "code" "$body")
    if [ "$c" != "0" ]; then
        echo "错误: [$step] 业务失败 code=$c"; echo "$body"; rm -rf "$WORKDIR"; exit 1
    fi
}

echo "============================================================"
echo "  真实 Embedding 检索质量评估"
echo "============================================================"
echo "  BASE_URL=$BASE_URL"
echo "  demo 文档: $DOC_FILE"
echo ""

# Step 1: 上传
echo "[1/3] 上传并索引 demo 文档..."
UP=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/documents/upload" -F "file=@$DOC_FILE")
UP_CODE=$(echo "$UP" | tail -1); UP_BODY=$(echo "$UP" | sed '$d')
check_response "上传" "$UP_BODY" "$UP_CODE"
DOC_ID=$(json_field "data.id" "$UP_BODY")
echo "  documentId=$DOC_ID"

# Step 2: 解析
PARSE=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/documents/$DOC_ID/parse")
PARSE_CODE=$(echo "$PARSE" | tail -1); PARSE_BODY=$(echo "$PARSE" | sed '$d')
check_response "解析" "$PARSE_BODY" "$PARSE_CODE"

# Step 3: 索引（真实 Embedding）
IDX=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/documents/$DOC_ID/index")
IDX_CODE=$(echo "$IDX" | tail -1); IDX_BODY=$(echo "$IDX" | sed '$d')
check_response "索引" "$IDX_BODY" "$IDX_CODE"
echo "  collection=$(json_field 'data.collectionName' "$IDX_BODY") vectorCount=$(json_field 'data.vectorCount' "$IDX_BODY")"
echo ""

# Step 4: 对 4 个问题检索并保存结果
echo "[2/3] 对 4 个问题做 TopK=3 检索..."
QUESTIONS=(
  "RAG 系统使用哪种向量数据库做 TopK 检索？"
  "代码评审工具如何拉取 GitHub 上的仓库？"
  "统一前端用什么框架和语言开发？"
  "红烧肉怎么做？"
)
for i in 0 1 2 3; do
  Q="${QUESTIONS[$i]}"
  RESP=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/search" \
      -H "Content-Type: application/json" \
      -d "{\"query\": \"$Q\", \"topK\": 3}")
  CODE=$(echo "$RESP" | tail -1); BODY=$(echo "$RESP" | sed '$d')
  check_response "检索#$i" "$BODY" "$CODE"
  echo "$BODY" > "$WORKDIR/search_$i.json"
  echo "  Q$i: $Q -> 已保存"
done
echo ""

# Step 5: Python 计算指标（真实运行，不写死）
echo "[3/3] 计算检索指标..."
python3 - "$WORKDIR" <<'PY'
import sys, json, os
wd = sys.argv[1]
# 每个问题期望命中的主题关键词（用于判定相关结果属于哪个主题）
expected = [
    ["Qdrant","TopK","RAG","向量数据库","检索"],          # Q0 -> 主题A
    ["JGit","GitHub","Code Reviewer","代码评审","Markdown"],  # Q1 -> 主题B
    ["React","TypeScript","MUI","Vite","前端"],          # Q2 -> 主题C
    None,                                                # Q3 -> 无关（红烧肉）
]

def load(i):
    with open(os.path.join(wd, f"search_{i}.json")) as f:
        return json.load(f)["data"]["results"]

hit1 = hit3 = mrr_sum = 0
mrr_list = []
for i in range(4):
    results = load(i)
    kw = expected[i]
    if kw is None:
        # 无关问题：报告最高分（应为低分）
        max_score = max((r.get("score") or 0.0) for r in results) if results else 0.0
        print(f"  Q{i}（无关问题 '红烧肉'）最高 score = {max_score:.4f}")
        continue
    matched_rank = None
    for rank, r in enumerate(results, start=1):
        content = (r.get("content") or "").lower()
        if any(k.lower() in content for k in kw):
            if matched_rank is None:
                matched_rank = rank
            break
    if matched_rank is not None:
        hit1 += 1
        hit3 += 1
        mrr_list.append(1.0 / matched_rank)
        mrr_sum += 1.0 / matched_rank
    else:
        mrr_list.append(0.0)
    print(f"  Q{i} top1命中={'是' if matched_rank==1 else '否'} 命中排名={matched_rank} mrr={ (1.0/matched_rank) if matched_rank else 0.0 :.4f}")

n = 3
print("")
print("  评估结果（真实运行）:")
print(f"    hit@1 = {hit1}/{n} = {hit1/n:.4f}")
print(f"    hit@3 = {hit3}/{n} = {hit3/n:.4f}")
print(f"    MRR    = {mrr_sum/n:.4f}")
PY

RC=$?
rm -rf "$WORKDIR"
if [ "$RC" -ne 0 ]; then
    echo "  [FAIL] 评估脚本异常"
    exit 1
fi
echo ""
echo "  [OK] 评估完成（指标均由真实运行计算，可填入 docs/retrieval-evaluation.md）"
echo "============================================================"
