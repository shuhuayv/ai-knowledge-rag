# Demo / Recording Script — ai-knowledge-rag

> 目标录屏长度 **2–3 分钟**。本 Gate **不实际录屏**，仅提供脚本；正式录屏由 Master AI 单独决定是否开新 Gate。
> 录屏时走真实模式需本机 Keychain 注入 Key；脚本不调用任何 REAL AI，仅描述操作顺序。
> Demo 不需要把所有 internal governance 展示出来，聚焦"上传→检索→问答→引用"主链路与工程亮点。

---

## 0. 录屏前准备（不出现在镜头里）

- 启动依赖：mysql8（3307）、redis7（6379）、qdrant（6333）已起。
- 真实模式：本机 Keychain 已注入 `ai_dev`（DB）与 `ai-knowledge-rag-zhipu`（智谱 Key）。
- `bash scripts/reset_demo_data.sh --yes` 重置为干净 demo 数据。

## 1. 项目主页 / 架构一句话（~15s）

- 展示 README 顶部与"系统架构"图。
- 口播："这是一个 Spring Boot + RAG 的知识库问答系统：上传文档 → 解析切块 → 真实向量化 → Qdrant 检索 → LLM 生成带引用的答案。"

## 2. 文档列表（~15s）

- 打开文档管理页面 / `GET /api/documents`，展示当前 active 文档（IDs 2,3,5,6）。
- 口播："当前活跃文档 4 份，这是经过历史去重治理后的状态。"

## 3. 上传 / duplicate 行为（~25s）

- 上传 `samples/company_policy.txt`（已存在）→ 展示返回的是**已有文档**（幂等，不新增）。
- 口播："我们用原始字节哈希做文档身份，重复上传直接返回已有文档，不会污染语料。"

## 4. parse / index（~15s）

- 展示切块与向量化流程（Mock 或 Real 均可，镜头展示日志中 embedding 维度：Real=1024 / Mock=384）。
- 口播："Mock 与 Real 的 Collection 物理隔离，维度不同，互不干扰。"

## 5. Search（~25s）

- `POST /api/search` 一个典型问题 → 展示 TopK 结果与 score。
- 口播："检索对所有未知/已删文档 fail closed，保证只召回活跃文档。"

## 6. Ask（~30s）

- `POST /api/rag/ask` 同一问题 → 展示生成的答案。
- 口播："RAG 把检索片段拼进 Prompt，调用真实智谱 glm-4.5-air 生成。"

## 7. references（~15s）

- 展开答案下方的 references，展示 `documentId / chunkId / score`。
- 口播："每个答案都带可追溯的引用来源。"

## 8. MySQL active documents（~15s）

- 简单 `SELECT id, is_deleted FROM kb_document ORDER BY id` 截图（脱敏，不展示连接串/密码）。
- 口播："数据库里历史重复行以 soft-delete 保留审计，活跃副本唯一。"

## 9. Qdrant REAL collection（~15s）

- 展示 REAL Collection `kb_chunks_zhipu_embedding_3_1024_v1` 的 points（5 条）。
- 口播："REAL 向量点用确定性 UUID v3 做幂等 upsert。"

## 10. 一张 benchmark 结果（~20s）

- 展示受控 17-case 评估表：Historical vs Post-dedup（Hit@3 0.5→1.0、MRR 0.6→0.75、faithfulness 0.875→0.9375）。
- 口播："这是小型受控基准，不代表生产准确率；我们如实标注 latency 本轮更高。"

## 11. 总结工程亮点（~20s）

- 口播三句话：① 两级身份设计（文档级哈希 + 向量点 UUID v3）；② 历史去重治理 + 分闸迁移；③ Gate 化证据验证与诚实边界。
- 收尾："这是一个本地/受控开发项目，重点是数据治理与工程纪律。"

---

## 备注（给录制者）

- 不展示：`.env`、Keychain 内容、数据库连接串、完整 Ask/Search 响应体、chunk 正文、Qdrant 原始向量。
- 若走 Real 模式，确保 Key 已注入；Mock 模式无需 Key 即可演示主链路（除真实 embedding/chat 外）。
- 录屏若超时，可裁剪第 8、9 步，保留 1–7 与 10–11。
