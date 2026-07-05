#!/bin/bash
# ===========================================
# 启动 Qdrant 向量数据库（Docker）
# 使用方式：bash scripts/start_qdrant.sh
# 前置条件：Docker 已安装并运行
# ===========================================

set -e

echo "========================================="
echo "启动 Qdrant 向量数据库"
echo "========================================="

# 删除旧容器（如果存在）
docker rm -f qdrant 2>/dev/null || true

# 拉取最新镜像
echo "拉取 Qdrant 镜像..."
docker pull qdrant/qdrant

# 启动容器
echo "启动 Qdrant 容器..."
docker run -d --name qdrant \
    -p 6333:6333 \
    -p 6334:6334 \
    qdrant/qdrant

# 等待服务就绪
echo "等待 Qdrant 启动..."
sleep 3

# 验证
echo "验证 Qdrant 状态..."
curl -s http://localhost:6333 | head -c 200
echo ""

echo "========================================="
echo "Qdrant 启动成功！"
echo "HTTP API: http://localhost:6333"
echo "gRPC API: http://localhost:6334"
echo "========================================="