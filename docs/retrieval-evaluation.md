# 检索与生成质量评估（真实运行）

> 本文档描述 ai-knowledge-rag 的**正式评估系统**（位于 `evaluation/`），基于真实智谱 `embedding-3`（1024 维）+ 真实 Qdrant + 真实 `glm-4.5-air` Chat。
> 所有指标为 2026-07-12 本机真实运行结果（base commit `51f6bc3`）。密钥经 macOS Keychain 在进程内临时读取，绝不出现于本报告、命令或日志。

## 1. 评估系统组成

| 文件 | 说明 |
|------|------|
| `evaluation/metrics.py` | 纯函数指标库：通用嵌套路径 `dig`、相关性判定、`first_relevant_rank`、`hit_at_k`、`mrr`、`percentile`、`latency_stats`、`summarize_retrieval` |
| `evaluation/retrieval_eval.py` | 检索评估：调用 `/api/search`，文档级命中，逐样本脱敏（documentId→文件名映射） |
| `evaluation/generation_eval.py` | 生成评估：调用 `/api/rag/ask`，单并发 + 礼貌延迟，仅存脱敏引用（documentId/chunkId/score） |
| `evaluation/test_metrics.py` | 评估脚本自身单测（18 例，覆盖 `dig`/相关性/排名/Hit/MRR/分位数/延迟/汇总） |
| `evaluation/dataset.json` | 17 条小型受控评估集（16 应检索 + 1 无答案） |
| `evaluation/README.md` | 使用说明、安全约定、指标口径、可配置项 |

依赖：**Python 标准库，无第三方依赖**；评估脚本自身单测为离线 `unittest`。

## 2. 运行方式

```bash
python3 evaluation/test_metrics.py                                   # 评估脚本单测（18 例）
python3 evaluation/retrieval_eval.py --base-url http://localhost:8080 --out-dir evaluation/results
python3 evaluation/generation_eval.py --base-url http://localhost:8080 --out-dir evaluation/results --delay 1.0
```

> 需先以真实 embedding 模式完成文档解析与向量化索引（见仓库 `README.md`「真实 Embedding」）。
> 密钥由运行机器经 `security find-generic-password` 从 Keychain 读取，仅注入当前进程环境，结束后立即 `unset`；不写命令、文件或日志。

## 3. 评估集说明

17 条问题覆盖：精确事实、数字/金额、时间/假期、制度规则、同义表达、多段信息、相似文档干扰、无答案。每条含 `id / category / question / expectedSources / expectedKeywords / topK / shouldRetrieve / 备注`，预期严格依据文档真实内容标注，**未反向篡改**。

语料取自用户当前已索引文档 `company_policy.txt`、`qa_sample_doc.txt`、`rag-demo.txt`（重复 4 份），属**小型受控评估集**，非大规模生产基准。

## 4. 指标定义

- **Hit@k**：文档级——期望文档（按文件名映射）出现在 top-k 即计命中；关键词仅作参考不计入命中，避免通用关键词在无关文档上的误命中。
- **MRR**：`MRR = mean(1 / rank)`，rank 为第一个命中结果的位置（1-based）；未命中记 0。
- **无结果率**：返回 0 条结果或 HTTP 非 2xx 的样本占比。
- **HTTP/解析错误率**：请求异常、超时或非法 JSON 的样本占比（单独记录，不计入零分）。
- **无答案正确拒答率**：`shouldRetrieve=false` 的样本中，未编造答案的占比。
- **忠实度（启发式）**：成功回答是否包含数据集标注的 `expectedKeywords`；**非绝对客观质量评分**。
- **延迟分位数**：P50 / P95 采用线性插值。

## 5. 真实运行指标（2026-07-12）

| 维度 | 指标 | 值 |
|------|------|-----|
| 检索 | 样本数 / 检索问题 | 17 / 16 |
| 检索 | Hit@1 / Hit@3 / Hit@5 | 0.5 / 0.5 / 1.0 |
| 检索 | MRR | 0.6 |
| 检索 | 无结果率 / HTTP·解析错误率 | 0.0 / 0.0 |
| 检索 | 无答案正确拒答率 | 1.0 |
| 检索 | 平均 / P50 / P95 延迟 | 1044.82 / 1056.0 / 1463.06 ms |
| 生成 | 成功率 / 失败 / 空回答 | 1.0 / 0 / 0 |
| 生成 | 引用率 | 1.0 |
| 生成 | 忠实度启发式 | 0.875（2 条为措辞/空格差异误判，非事实错误） |
| 生成 | 限流 / 超时 | 0 / 0 |
| 生成 | 平均 / P50 / P95 延迟 | 2596.51 / 2510.6 / 3799.0 ms |

## 6. 失败样本与污染分析

1. **重复文档污染**：4 份相同 `rag-demo.txt` 占据多数政策类问题 top-1~top-4，将 `company_policy.txt` 相关 chunk 推至 rank-5，导致 Hit@1/3=0.5、Hit@5=1.0。建议对重复/近似文档做去重或内容指纹。
2. **分块粒度偏粗**：`company_policy.txt` 仅切分为 2 个 chunk（约 2079 字节），检索为文档级命中；建议按小节/条款细化分块。
3. **生成侧无异常**：17/17 成功且均带引用，未触发限流；平均延迟约 2.6s、P95 约 3.8s，属真实 Chat 正常区间。
4. **无答案处理正确**：q17 正确拒答未提供菜单，未编造。

## 7. 诚实边界与限制

- 本评估为**小型受控**基准，样本 17 条、语料含重复演示文档，不能外推为生产准确率。
- 忠实度指标为**启发式**（关键词包含），非 LLM-as-judge 绝对质量评分。
- 未做：训练模型、自研向量库、线上高并发、Diff Review。
- 完整逐样本脱敏结果见 `evaluation/results/retrieval_samples.json`、`generation_samples.json`、`run_metadata.json`。
