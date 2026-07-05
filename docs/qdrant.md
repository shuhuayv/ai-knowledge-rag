# Qdrant 向量数据库

## 概述

Qdrant 是一个高性能的开源向量数据库，本系统使用 Qdrant 作为 RAG（检索增强生成）的向量存储引擎。

## Docker 启动命令

```bash
# 拉取镜像
docker pull qdrant/qdrant

# 启动容器（API 端口 6333，gRPC 端口 6334）
docker run -d --name qdrant \
  -p 6333:6333 \
  -p 6334:6334 \
  qdrant/qdrant
```

## 验证启动

```bash
curl http://localhost:6333
```

预期返回：

```json
{"title":"qdrant - vector search engine","version":"1.18.2"}
```

## 管理接口

| 地址 | 说明 |
|---|---|
| http://localhost:6333 | REST API |
| http://localhost:6334 | gRPC API |
| http://localhost:6333/dashboard | Web 管理面板 |

## 常用操作

```bash
# 查看运行状态
docker ps | grep qdrant

# 停止容器
docker stop qdrant

# 重新启动
docker start qdrant
```

## 当前状态

Qdrant 已部署并运行中，已接入向量化业务。

### 当前配置

| 配置项 | 值 | 说明 |
|---|---|---|
| Collection 名称 | `kb_chunks` | 可在 application.yml 中修改 |
| 向量维度 | 384 | 当前 Mock Embedding 维度 |
| 距离度量 | Cosine | 余弦相似度 |

### 已接入功能

- 创建 Collection（自动创建）
- Upsert Point（写入向量 + Payload）
- Payload 包含：documentId、chunkId、chunkIndex、content

### 后续阶段

- TopK 相似性检索（向量搜索）
- RAG 问答上下文召回
- 替换 Mock Embedding 为真实 Embedding 模型