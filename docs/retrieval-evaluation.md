# 检索质量评估方法

> 本文档说明真实 Embedding 接入后的检索质量评估方法与指标定义。
> 指标**真实值需由用户在本机运行 `scripts/evaluate_real_embedding.sh` 后填入**（脚本实时计算，不写死）。

## 评估目标

验证真实 Embedding（智谱 `embedding-3`，1024 维）相较 Mock 伪向量，能按语义正确召回相关文档片段。

## 评估数据

`scripts/evaluate_real_embedding.sh` 会自动生成一份含 **3 个主题** 的 demo 文档：

- **主题 A**：RAG / Qdrant / TopK 向量检索
- **主题 B**：Code Reviewer / JGit / GitHub / Markdown
- **主题 C**：统一前端 / React / TypeScript / MUI / Vite

然后对 **4 个问题** 做 TopK=3 检索：

| 问题 | 期望主题 | 类型 |
|------|----------|------|
| RAG 系统使用哪种向量数据库做 TopK 检索？ | A | 相关 |
| 代码评审工具如何拉取 GitHub 上的仓库？ | B | 相关 |
| 统一前端用什么框架和语言开发？ | C | 相关 |
| 红烧肉怎么做？ | 无 | 无关（负例） |

## 指标定义

- **hit@1**：Top1 结果是否命中期望主题（命中=1，否则=0）。
- **hit@3**：Top3 结果中是否至少 1 个命中期望主题。
- **MRR（Mean Reciprocal Rank）**：`MRR = mean(1 / rank)`，rank 为第一个命中结果的位置（1-based）；未命中记 0。
- **无关问题最高分**：对"红烧肉"这类无关问题，返回结果中的最高 `score`，用于观察跨主题干扰强度（预期较低）。

判定"命中"的规则：结果 `content` 包含期望主题的任一关键词（如主题 A 含 `Qdrant`/`TopK`/`RAG` 等）。

## 运行方式

```bash
# 1. 以真实 Embedding 模式启动后端
export AI_EMBEDDING_PROVIDER=zhipu
export ZHIPU_API_KEY='<your-local-api-key>'
mvn spring-boot:run

# 2. 另开终端运行评估脚本
bash scripts/evaluate_real_embedding.sh
```

脚本会完成 上传 → 解析 → 索引 → 4 次检索 → 实时计算并打印上述指标。

## 指标占位（请运行后填写真实值）

> 以下为占位，请在本机真实运行 `scripts/evaluate_real_embedding.sh` 后替换。

| 指标 | 真实值（运行后填） | 说明 |
|------|-------------------|------|
| hit@1 | `<TBD>` | 期望 = 1.000（3/3 全部 Top1 命中） |
| hit@3 | `<TBD>` | 期望 = 1.000 |
| MRR | `<TBD>` | 期望接近 1.000 |
| 无关问题（红烧肉）最高分 | `<TBD>` | 期望明显低于相关主题得分 |

## 对比建议

- 同一份文档与问题，分别用 Mock（384）与真实（1024）索引后运行评估，对比 hit@1 / MRR；
- 真实模式预期显著优于 Mock（Mock 仅字符 hash，无语义）；
- 若真实模式指标偏低，检查：文档是否充分覆盖主题关键词、min-score 是否过高、`embedding-3` 是否对中文语义友好。
