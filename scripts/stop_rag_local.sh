#!/bin/bash
set -u
for pid in $(lsof -ti tcp:8080 2>/dev/null || true); do
  command_line="$(ps -p "$pid" -o command= 2>/dev/null || true)"
  if [[ "$command_line" == *"ai-knowledge-rag"* ]] || [[ "$command_line" == *"AiKnowledgeRagApplication"* ]]; then
    kill "$pid" 2>/dev/null || true
  fi
done
echo "RAG 已停止"
