#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
真实 Embedding 检索质量评估（与生成评估分离）。

- 调用运行中的 RAG 服务 /api/search（服务端使用真实智谱 Embedding + 真实 Qdrant）。
- 不做任何指标写死；全部由真实响应计算。
- 修复旧脚本的嵌套路径读取 bug（data.id 等），HTTP 非 2xx / JSON 解析失败 /
  业务失败均单独记录为失败样本，绝不算作“零分命中”。
- 不读取/打印/落盘任何 API Key 或数据库密码；仅通过 BASE_URL 调用本地服务。
- 逐样本结果脱敏：仅记录文件名（不记录文档原文），API Key 不出现。

退出码：
  0  评估完成（允许个别样本失败，失败计入指标）
  2  数据集文件缺失
  1  全部样本均失败（服务不可达等灾难性失败）
"""
import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from metrics import dig, is_relevant, first_relevant_rank, summarize_retrieval  # noqa: E402


def http_post_json(url, payload, timeout):
    req = urllib.request.Request(
        url,
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return resp.status, resp.read().decode("utf-8")


def fetch_doc_index(base_url, timeout):
    """预拉取文档列表，建立 id->fileName 映射（仅文件名，不记录原文）。"""
    url = f"{base_url}/api/documents"
    try:
        with urllib.request.urlopen(url, timeout=timeout) as resp:
            data = json.loads(resp.read().decode("utf-8"))
        docs = data.get("data") or []
        return {d["id"]: d.get("fileName") for d in docs}
    except Exception:
        return {}


def evaluate_sample(sample, base_url, id2name, timeout):
    q = sample.get("question")
    topk = int(sample.get("topK") or 5)
    expected_sources = set(sample.get("expectedSources") or [])
    expected_keywords = sample.get("expectedKeywords") or []
    should_retrieve = bool(sample.get("shouldRetrieve", True))

    rec = {
        "id": sample.get("id"),
        "category": sample.get("category"),
        "question": q,
        "topK": topk,
        "shouldRetrieve": should_retrieve,
        "expectedSources": sorted(expected_sources),
        "expectedKeywords": expected_keywords,
    }
    t0 = time.time()
    try:
        code, body = http_post_json(
            f"{base_url}/api/search",
            {"query": q, "topK": topk},
            timeout,
        )
    except urllib.error.HTTPError as e:
        rec["failure_type"] = f"http_{e.code}"
        rec["latency_ms"] = round((time.time() - t0) * 1000, 1)
        return rec
    except Exception as e:  # 网络错误 / 超时
        rec["failure_type"] = "network_error"
        rec["error_detail"] = type(e).__name__
        rec["latency_ms"] = round((time.time() - t0) * 1000, 1)
        return rec

    rec["latency_ms"] = round((time.time() - t0) * 1000, 1)

    try:
        parsed = json.loads(body)
    except json.JSONDecodeError:
        rec["failure_type"] = "parse_error"
        return rec

    biz = dig(parsed, "code")
    if biz != 0:
        rec["failure_type"] = f"business_error:{biz}"
        rec["error_detail"] = dig(parsed, "message")
        return rec

    data = dig(parsed, "data") or {}
    results = dig(data, "results") or []
    # 注入匿名化来源（文件名）供相关性判定
    for r in results:
        r["source"] = id2name.get(r.get("documentId"), f"doc-{r.get('documentId')}")

    rec["result_count"] = len(results)
    rec["returned_sources"] = [r.get("source") for r in results]

    # 官方命中 = 文档级（期望文档出现在 top-k）；关键词匹配仅作参考，不计入命中，
    # 避免通用关键词（如“5天”）在无关文档上的误命中。
    rank = first_relevant_rank(results, expected_sources, []) if expected_sources else None
    kw_rank = first_relevant_rank(results, set(), expected_keywords) if expected_keywords else None
    rec["rank"] = rank
    rec["hit"] = rank is not None
    rec["keyword_hit"] = kw_rank is not None
    # 命中来源（脱敏：仅文件名 + 排名）
    if rank is not None:
        rec["hit_source"] = results[rank - 1].get("source")
    return rec


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base-url", default=os.environ.get("BASE_URL", "http://localhost:8080"))
    ap.add_argument("--dataset", default=os.environ.get("EVAL_DATASET",
                                                        os.path.join(os.path.dirname(os.path.abspath(__file__)), "dataset.json")))
    ap.add_argument("--out-dir", default=os.environ.get("EVAL_OUT",
                                                        os.path.join(os.path.dirname(os.path.abspath(__file__)), "results")))
    ap.add_argument("--timeout", type=int, default=int(os.environ.get("EVAL_TIMEOUT", "30")))
    args = ap.parse_args()

    if not os.path.exists(args.dataset):
        sys.stderr.write(f"[FATAL] 数据集不存在: {args.dataset}\n")
        return 2

    with open(args.dataset, encoding="utf-8") as f:
        dataset = json.load(f)
    samples_in = dataset.get("samples") or dataset.get("data") or []

    id2name = fetch_doc_index(args.base_url, args.timeout)
    print(f"[info] BASE_URL={args.base_url} docs_indexed={len(id2name)} samples={len(samples_in)}")

    out_samples = [evaluate_sample(s, args.base_url, id2name, args.timeout) for s in samples_in]
    metrics = summarize_retrieval(out_samples)

    os.makedirs(args.out_dir, exist_ok=True)
    with open(os.path.join(args.out_dir, "retrieval_samples.json"), "w", encoding="utf-8") as f:
        json.dump(out_samples, f, ensure_ascii=False, indent=2)
    with open(os.path.join(args.out_dir, "retrieval_metrics.json"), "w", encoding="utf-8") as f:
        json.dump(metrics, f, ensure_ascii=False, indent=2)

    md = build_markdown(metrics, out_samples, args)
    with open(os.path.join(args.out_dir, "retrieval_report.md"), "w", encoding="utf-8") as f:
        f.write(md)
    print(md)
    print(f"[info] 指标已写入 {args.out_dir}")

    valid = [s for s in out_samples if not s.get("failure_type")]
    # 灾难性失败（零有效样本）-> 非零退出
    if not valid:
        sys.stderr.write("[FATAL] 全部样本失败，服务可能不可达\n")
        return 1
    return 0


def build_markdown(metrics, samples, args):
    lines = []
    lines.append("# RAG 真实检索评估指标报告\n")
    lines.append(f"- 评估时间：{time.strftime('%Y-%m-%d %H:%M:%S')}")
    lines.append(f"- BASE_URL：`{args.base_url}`（脱敏，不含密钥）")
    lines.append(f"- 样本总数：{metrics['total_samples']}")
    lines.append("")
    lines.append("## 汇总指标（由真实运行计算）\n")
    lines.append("| 指标 | 值 |")
    lines.append("| --- | --- |")
    lines.append(f"| 样本总数 | {metrics['total_samples']} |")
    lines.append(f"| 有效样本 | {metrics['valid_samples']} |")
    lines.append(f"| 成功检索（返回≥1结果） | {metrics['successful_retrievals']} |")
    lines.append(f"| 失败样本 | {metrics['failed_samples']} |")
    lines.append(f"| 无结果样本 | {metrics['no_result_samples']} |")
    lines.append(f"| 检索类问题数 | {metrics['retrieval_questions']} |")
    lines.append(f"| Hit@1 | {metrics['hit@1']} |")
    lines.append(f"| Hit@3 | {metrics['hit@3']} |")
    lines.append(f"| Hit@5 | {metrics['hit@5']} |")
    lines.append(f"| MRR | {metrics['mrr']} |")
    lines.append(f"| 无结果率 | {metrics['no_result_rate']} |")
    lines.append(f"| HTTP/解析/业务错误率 | {metrics['http_parse_error_rate']} |")
    if metrics['no_answer_correct_rate'] is not None:
        lines.append(f"| 无答案正确abstain率 | {metrics['no_answer_correct_rate']} |")
    lat = metrics["latency"]
    lines.append(f"| 平均延迟(ms) | {lat['avg_ms']} |")
    lines.append(f"| P50延迟(ms) | {lat['p50_ms']} |")
    lines.append(f"| P95延迟(ms) | {lat['p95_ms']} |")
    lines.append("")
    lines.append("## 逐样本（脱敏）\n")
    lines.append("| id | 类别 | 命中 | 排名 | 返回来源 | 延迟(ms) | 失败类型 |")
    lines.append("| --- | --- | --- | --- | --- | --- | --- |")
    for s in samples:
        srcs = ", ".join(s.get("returned_sources") or [])
        lines.append(
            f"| {s.get('id')} | {s.get('category')} | {'是' if s.get('hit') else '否'} | "
            f"{s.get('rank') or '-'} | {srcs} | {s.get('latency_ms')} | {s.get('failure_type') or '-'} |"
        )
    lines.append("")
    lines.append("> 说明：本评估使用运行中的 RAG 服务（真实智谱 Embedding + 真实 Qdrant）对")
    lines.append("> 已索引文档做语义检索；指标全部由真实响应计算，未写死。详细结果见")
    lines.append("> `retrieval_samples.json` 与 `retrieval_metrics.json`。")
    return "\n".join(lines) + "\n"


if __name__ == "__main__":
    sys.exit(main())
