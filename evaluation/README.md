# RAG 评估套件（evaluation/）

对运行中的 RAG 服务做**真实**检索质量与生成质量评估。检索评估与生成评估**分开运行**。

## 前置条件

- RAG 服务已启动，且处于**真实 zhipu 模式**：
  - `GET /api/embedding/status` 返回 `provider=zhipu, model=embedding-3, dimensions=1024, mode=REAL, fallbackEnabled=false`
  - `POST /api/rag/ask` 返回 `provider=zhipu, model=glm-4.5-air`（真实 Chat）
- 依赖：Python 3.8+，**仅标准库**（无需 pip 安装）。

## 文件结构

| 文件 | 说明 |
|------|------|
| `metrics.py` | 通用指标库（嵌套路径读取 `dig`、命中/MRR/分位数/延迟统计）。纯函数，无网络。 |
| `retrieval_eval.py` | 检索评估：调用 `/api/search`，计算 Hit@k / MRR / 延迟，逐样本脱敏。 |
| `generation_eval.py` | 生成评估：调用 `/api/rag/ask`，单并发，记录答案/引用/延迟/忠实度启发式。 |
| `test_metrics.py` | `metrics.py` 的离线单元测试（无需服务）。 |
| `dataset.json` | 评估集（17 条，含预期来源/关键词/类别）。小型受控集，非生产基准。 |
| `results/` | 运行产物：指标 JSON、逐样本 JSON、Markdown 报告、运行元数据。 |

## 运行

```bash
# 0) 离线单元测试（CI 可跑，无需服务）
python3 evaluation/test_metrics.py

# 1) 检索评估（服务端真实 Embedding + Qdrant）
python3 evaluation/retrieval_eval.py --base-url http://localhost:8080 --out-dir evaluation/results

# 2) 生成评估（服务端真实 Chat，单并发 + 退避）
python3 evaluation/generation_eval.py --base-url http://localhost:8080 --out-dir evaluation/results --delay 1.0
```

## 安全约定（强制）

- **不读取/打印/落盘任何 API Key 或数据库密码。**
- 密钥仅在运行机器经 `security find-generic-password` 从 macOS Keychain 读取，注入当前进程环境，结束即 `unset`。
- 评估脚本本身只通过 `BASE_URL` 调用本地服务，不持有密钥。
- 输出已脱敏：仅记录文档文件名（不记录原文）、引用来源标识（documentId/chunkId/score，不含内容）、不保存 `promptPreview`。

## 指标口径

- **检索命中（文档级）**：期望文档（按文件名）出现在 top-k 即计为命中。关键词仅作参考、**不计入**命中，避免通用关键词（如“5天”）在无关文档上的误命中。
- **无答案样本**：`shouldRetrieve=false`，正确 abstain（top-k 不含期望来源）计为通过。
- **HTTP 非 2xx / JSON 解析失败 / 业务失败**：单独记录为失败样本，**绝不**算作零分命中；脚本对个别样本失败容忍，仅当全部失败时非零退出。
- **生成忠实度**：透明启发式（答案是否包含标注关键词），**非绝对客观质量评分**，评测标准公开声明。

## 可配置项（环境变量或命令行，均可配置，无硬编码用户目录/密钥）

- `BASE_URL` / `--base-url`
- `EVAL_DATASET` / `--dataset`
- `EVAL_OUT` / `--out-dir`
- `EVAL_TIMEOUT` / `--timeout`
- `EVAL_DELAY` / `--delay`（仅生成评估，调用间礼貌延迟）

## 结果解读

最新一次真实运行结果见 `results/run_metadata.json` 与两份报告。结论（真实运行，非写死）：

- 检索：Hit@1=0.5，Hit@3=0.5，Hit@5=1.0，MRR=0.6；重复 `rag-demo.txt` 文档把部分政策问题推至 rank-5。
- 生成：17/17 成功，引用包含率 1.0，平均延迟约 2.6s，未触发限流。

> 本评估集取自用户当前已索引文档，为**小型受控评估集**，用于演示与回归，不构成大规模生产基准。
