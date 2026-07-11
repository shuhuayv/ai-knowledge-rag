#!/bin/bash
set -Eeuo pipefail
BASE_DIR="$HOME/Developer/ai-internship-july"
RAG_DIR="$BASE_DIR/ai-knowledge-rag"
RUN_DIR="$BASE_DIR/.demo-run"
mkdir -p "$RUN_DIR/logs"

DB_PASSWORD="$(security find-generic-password -a ai_dev -w 2>/dev/null || true)"
ZHIPU_API_KEY="$(security find-generic-password -a zhipu -s ai-knowledge-rag-zhipu -w 2>/dev/null || true)"
[[ -n "$DB_PASSWORD" ]] || { echo "未找到 ai_dev Keychain 密码" >&2; exit 1; }
[[ -n "$ZHIPU_API_KEY" ]] || { echo "未找到智谱 Keychain Key" >&2; exit 1; }

for pid in $(lsof -ti tcp:8080 2>/dev/null || true); do
  command_line="$(ps -p "$pid" -o command= 2>/dev/null || true)"
  if [[ "$command_line" == *"ai-knowledge-rag"* ]] || [[ "$command_line" == *"AiKnowledgeRagApplication"* ]] || [[ "$command_line" == *"spring-boot:run"* ]]; then
    kill "$pid" 2>/dev/null || true
  else
    echo "8080 被非 RAG 程序占用：PID=${pid} ${command_line}" >&2
    exit 1
  fi
done
sleep 2

cd "$RAG_DIR"
jar_file=""
for candidate in target/ai-knowledge-rag-*.jar; do
  [[ -f "$candidate" ]] || continue
  [[ "$candidate" == *.original ]] && continue
  jar_file="$candidate"
  break
done
[[ -n "$jar_file" ]] || { echo "未找到 RAG JAR" >&2; exit 1; }

nohup env \
  DB_HOST=127.0.0.1 DB_PORT=3307 DB_NAME=ai_knowledge_rag DB_USERNAME=ai_dev DB_PASSWORD="$DB_PASSWORD" \
  REDIS_HOST=127.0.0.1 REDIS_PORT=6379 QDRANT_URL=http://127.0.0.1:6333 \
  AI_EMBEDDING_PROVIDER=zhipu AI_EMBEDDING_MODEL=embedding-3 AI_EMBEDDING_DIMENSIONS=1024 \
  AI_EMBEDDING_FALLBACK_ENABLED=false AI_MOCK_ENABLED=false AI_PROVIDER=zhipu AI_MODEL=glm-4.5-air \
  AI_MAX_TOKENS=4096 AI_THINKING_TYPE=disabled \
  AI_TIMEOUT_SECONDS=90 AI_CHAT_MAX_RETRIES=3 \
  ZHIPU_API_KEY="$ZHIPU_API_KEY" \
  java -jar "$jar_file" > "$RUN_DIR/logs/rag-fixed.log" 2>&1 &
echo $! > "$RUN_DIR/rag-fixed.pid"
unset DB_PASSWORD ZHIPU_API_KEY

for _ in $(seq 1 90); do
  code="$(curl -sS -o /tmp/rag-documents.json -w '%{http_code}' --max-time 3 http://localhost:8080/api/documents 2>/dev/null || true)"
  if [[ "$code" =~ ^2 ]]; then
    echo "RAG 已启动，/api/documents=${code}"
    exit 0
  fi
  sleep 1
done
tail -n 100 "$RUN_DIR/logs/rag-fixed.log" >&2 || true
exit 1
