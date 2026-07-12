# RAG 真实检索评估指标报告

- 评估时间：2026-07-12 12:11:44
- BASE_URL：`http://localhost:8080`（脱敏，不含密钥）
- 样本总数：17

## 汇总指标（由真实运行计算）

| 指标 | 值 |
| --- | --- |
| 样本总数 | 17 |
| 有效样本 | 17 |
| 成功检索（返回≥1结果） | 17 |
| 失败样本 | 0 |
| 无结果样本 | 0 |
| 检索类问题数 | 16 |
| Hit@1 | 0.5 |
| Hit@3 | 0.5 |
| Hit@5 | 1.0 |
| MRR | 0.6 |
| 无结果率 | 0.0 |
| HTTP/解析/业务错误率 | 0.0 |
| 无答案正确abstain率 | 1.0 |
| 平均延迟(ms) | 1044.82 |
| P50延迟(ms) | 1056.0 |
| P95延迟(ms) | 1463.06 |

## 逐样本（脱敏）

| id | 类别 | 命中 | 排名 | 返回来源 | 延迟(ms) | 失败类型 |
| --- | --- | --- | --- | --- | --- | --- |
| q01 | 精确事实 | 是 | 5 | rag-demo.txt, rag-demo.txt, rag-demo.txt, rag-demo.txt, company_policy.txt | 1778.1 | - |
| q02 | 数字/金额 | 是 | 5 | rag-demo.txt, rag-demo.txt, rag-demo.txt, rag-demo.txt, company_policy.txt | 1121.8 | - |
| q03 | 数字/金额 | 是 | 5 | rag-demo.txt, rag-demo.txt, rag-demo.txt, rag-demo.txt, company_policy.txt | 631.4 | - |
| q04 | 时间/假期 | 是 | 5 | rag-demo.txt, rag-demo.txt, rag-demo.txt, rag-demo.txt, company_policy.txt | 1376.2 | - |
| q05 | 制度规则 | 是 | 5 | rag-demo.txt, rag-demo.txt, rag-demo.txt, rag-demo.txt, company_policy.txt | 774.6 | - |
| q06 | 制度规则 | 是 | 1 | company_policy.txt, rag-demo.txt, rag-demo.txt, rag-demo.txt, rag-demo.txt | 1033.4 | - |
| q07 | 制度规则 | 是 | 1 | company_policy.txt, rag-demo.txt, rag-demo.txt, rag-demo.txt, rag-demo.txt | 693.0 | - |
| q08 | 数字/金额 | 是 | 5 | rag-demo.txt, rag-demo.txt, rag-demo.txt, rag-demo.txt, company_policy.txt | 1056.0 | - |
| q09 | 数字/金额 | 是 | 5 | rag-demo.txt, rag-demo.txt, rag-demo.txt, rag-demo.txt, company_policy.txt | 928.5 | - |
| q10 | 同义表达 | 是 | 1 | company_policy.txt, rag-demo.txt, rag-demo.txt, rag-demo.txt, rag-demo.txt | 759.4 | - |
| q11 | 多段信息 | 是 | 1 | company_policy.txt, rag-demo.txt, rag-demo.txt, rag-demo.txt, rag-demo.txt | 1384.3 | - |
| q12 | 数字/金额 | 是 | 5 | rag-demo.txt, rag-demo.txt, rag-demo.txt, rag-demo.txt, company_policy.txt | 940.0 | - |
| q13 | 精确事实 | 是 | 1 | qa_sample_doc.txt, 来自 ChatGPT 的便笺.txt, 来自 ChatGPT 的便笺.txt, company_policy.txt, company_policy.txt | 1072.6 | - |
| q14 | 数字/精确 | 是 | 1 | qa_sample_doc.txt, company_policy.txt, company_policy.txt, rag-demo.txt, rag-demo.txt | 1238.9 | - |
| q15 | 精确事实 | 是 | 1 | qa_sample_doc.txt, company_policy.txt, company_policy.txt, 来自 ChatGPT 的便笺.txt, 来自 ChatGPT 的便笺.txt | 718.7 | - |
| q16 | 相似文档干扰 | 是 | 1 | rag-demo.txt, rag-demo.txt, rag-demo.txt, rag-demo.txt, company_policy.txt | 1123.0 | - |
| q17 | 无答案 | 否 | - | rag-demo.txt, rag-demo.txt, rag-demo.txt, rag-demo.txt, 来自 ChatGPT 的便笺.txt | 1132.1 | - |

> 说明：本评估使用运行中的 RAG 服务（真实智谱 Embedding + 真实 Qdrant）对
> 已索引文档做语义检索；指标全部由真实响应计算，未写死。详细结果见
> `retrieval_samples.json` 与 `retrieval_metrics.json`。
