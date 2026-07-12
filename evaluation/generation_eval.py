#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
真实生成（Chat）质量评估 —— 与检索评估**分开**运行。

- 调用运行中的 RAG 服务 /api/rag/ask（服务端使用真实智谱 Chat：glm-4.5-air，
  thinking=disabled，单并发，沿用服务端退避重试）。
- 单并发 + 调用间礼貌延迟，避免连续快速请求触发限流；限流样本计入失败原因，
  绝不静默删除。
- 不读取/打印/落盘任何 API Key 或数据库密码。
- 不保存 promptPreview（含检索上下文），仅保存答案、引用来源（脱敏：documentId/
  chunkId/score，不含原文）、provider/model、耗时。
- 忠实度采用**透明启发式**（非绝对客观指标）：成功回答中是否包含数据集标注的
  expectedKeywords；评分标准公开声明，不构成绝对质量结论。

退出码：
  0  评估完成（允许个别样本失败，失败计入指标）
  2  数据集缺失
  1  全部样本失败
"""
import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from metrics import dig, latency_stats, safe_div  # noqa: E402


def http_post_json(url, payload, timeout):
    req = urllib.request.Request(
        url,
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return resp.status, resp.read().decode("utf-8")


def evaluate_sample(sample, base_url, timeout):
    q = sample.get("question")
    topk = int(sample.get("topK") or 5)
    expected_keywords = sample.get("expectedKeywords") or []

    rec = {
        "id": sample.get("id"),
        "category": sample.get("category"),
        "question": q,
        "topK": topk,
    }
    t0 = time.time()
    try:
        code, body = http_post_json(
            f"{base_url}/api/rag/ask",
            {"question": q, "topK": topk},
            timeout,
        )
    except urllib.error.HTTPError as e:
        rec["failure_type"] = f"http_{e.code}"
        rec["latency_ms"] = round((time.time() - t0) * 1000, 1)
        return rec
    except Exception as e:
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
    answer = dig(data, "answer") or ""
    rec["provider"] = dig(data, "provider")
    rec["model"] = dig(data, "model")
    rec["embeddingProvider"] = dig(data, "embeddingProvider")
    rec["embeddingMode"] = dig(data, "embeddingMode")
    rec["fallbackUsed"] = dig(data, "fallbackUsed")
    rec["referenceCount"] = dig(data, "referenceCount")
    rec["retrievalQualityNote"] = dig(data, "retrievalQualityNote")
    rec["answer"] = answer
    rec["answer_length"] = len(answer)

    refs = dig(data, "references") or []
    # 脱敏：仅保留来源标识与分数，不含原文
    rec["references"] = [
        {
            "documentId": r.get("documentId"),
            "chunkId": r.get("chunkId"),
            "chunkIndex": r.get("chunkIndex"),
            "score": r.get("score"),
        }
        for r in refs
    ]

    # 透明启发式忠实度（非绝对客观）：答案是否包含任一标注关键词
    if answer and expected_keywords:
        low = answer.lower()
        rec["faithfulness_heuristic"] = any(str(k).lower() in low for k in expected_keywords)
    else:
        rec["faithfulness_heuristic"] = None
    return rec


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base-url", default=os.environ.get("BASE_URL", "http://localhost:8080"))
    ap.add_argument("--dataset", default=os.environ.get("EVAL_DATASET",
                                                        os.path.join(os.path.dirname(os.path.abspath(__file__)), "dataset.json")))
    ap.add_argument("--out-dir", default=os.environ.get("EVAL_OUT",
                                                        os.path.join(os.path.dirname(os.path.abspath(__file__)), "results")))
    ap.add_argument("--timeout", type=int, default=int(os.environ.get("EVAL_TIMEOUT", "90")))
    ap.add_argument("--delay", type=float, default=float(os.environ.get("EVAL_DELAY", "1.0")),
                    help="调用间礼貌延迟（秒），避免连续请求触发限流")
    args = ap.parse_args()

    if not os.path.exists(args.dataset):
        sys.stderr.write(f"[FATAL] 数据集不存在: {args.dataset}\n")
        return 2

    with open(args.dataset, encoding="utf-8") as f:
        dataset = json.load(f)
    samples_in = dataset.get("samples") or dataset.get("data") or []

    out = []
    for i, s in enumerate(samples_in):
        rec = evaluate_sample(s, args.base_url, args.timeout)
        out.append(rec)
        status = "OK" if not rec.get("failure_type") else rec["failure_type"]
        print(f"[gen] {s.get('id')} -> {status} ({rec.get('latency_ms')}ms)")
        if i < len(samples_in) - 1:
            time.sleep(args.delay)

    metrics = summarize_generation(out)
    os.makedirs(args.out_dir, exist_ok=True)
    with open(os.path.join(args.out_dir, "generation_samples.json"), "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=2)
    with open(os.path.join(args.out_dir, "generation_metrics.json"), "w", encoding="utf-8") as f:
        json.dump(metrics, f, ensure_ascii=False, indent=2)

    md = build_markdown(metrics, out, args)
    with open(os.path.join(args.out_dir, "generation_report.md"), "w", encoding="utf-8") as f:
        f.write(md)
    print(md)
    print(f"[info] 生成评估已写入 {args.out_dir}")

    valid = [s for s in out if not s.get("failure_type")]
    if not valid:
        sys.stderr.write("[FATAL] 全部样本失败，服务可能不可达\n")
        return 1
    return 0


def summarize_generation(samples):
    total = len(samples)
    failures = [s for s in samples if s.get("failure_type")]
    ok = [s for s in samples if not s.get("failure_type")]
    success = [s for s in ok if (s.get("answer") or "").strip()]
    empty = [s for s in ok if not (s.get("answer") or "").strip()]
    rate_limited = [s for s in samples if (s.get("failure_type") or "").startswith("http_429")]
    timeout = [s for s in samples if (s.get("failure_type") or "") in ("network_error", "parse_error")
               or (s.get("failure_type") or "").startswith("http_5") or (s.get("failure_type") or "").startswith("http_4")]
    # 更精确：超时/解析归 timeout 类
    timeout = [s for s in samples if (s.get("failure_type") in ("network_error", "parse_error"))
               or (s.get("failure_type") or "").startswith(("http_5", "http_408"))]

    lats = [s["latency_ms"] for s in ok if "latency_ms" in s]
    lst = latency_stats(lats)
    ref_ok = [s for s in success if (s.get("referenceCount") or 0) > 0]
    faith = [s for s in success if s.get("faithfulness_heuristic") is True]
    faith_eval = [s for s in success if s.get("faithfulness_heuristic") is not None]

    return {
        "sample_count": total,
        "success_count": len(success),
        "success_rate": round(safe_div(len(success), total), 4),
        "failed_count": len(failures),
        "empty_answer_count": len(empty),
        "rate_limited_count": len(rate_limited),
        "timeout_count": len(timeout),
        "reference_inclusion_rate": round(safe_div(len(ref_ok), len(success)), 4) if success else 0.0,
        "faithfulness_heuristic_rate": round(safe_div(len(faith), len(faith_eval)), 4) if faith_eval else None,
        "faithfulness_note": "启发式：成功回答是否包含数据集标注的 expectedKeywords；非绝对客观质量评分",
        "latency": lst,
    }


def build_markdown(metrics, samples, args):
    lines = []
    lines.append("# RAG 真实生成（Chat）评估报告\n")
    lines.append(f"- 评估时间：{time.strftime('%Y-%m-%d %H:%M:%S')}")
    lines.append(f"- BASE_URL：`{args.base_url}`（脱敏，不含密钥）")
    lines.append(f"- 单并发 + 调用间延迟 {args.delay}s；服务端模型 glm-4.5-air / thinking=disabled / 退避重试")
    lines.append("")
    lines.append("## 汇总指标（由真实运行计算）\n")
    lines.append("| 指标 | 值 |")
    lines.append("| --- | --- |")
    lines.append(f"| 样本数 | {metrics['sample_count']} |")
    lines.append(f"| 成功数 | {metrics['success_count']} |")
    lines.append(f"| 成功率 | {metrics['success_rate']} |")
    lines.append(f"| 失败数 | {metrics['failed_count']} |")
    lines.append(f"| 空回答数 | {metrics['empty_answer_count']} |")
    lines.append(f"| 限流数 | {metrics['rate_limited_count']} |")
    lines.append(f"| 超时/解析错误数 | {metrics['timeout_count']} |")
    lines.append(f"| 引用包含率 | {metrics['reference_inclusion_rate']} |")
    if metrics['faithfulness_heuristic_rate'] is not None:
        lines.append(f"| 忠实度启发式率 | {metrics['faithfulness_heuristic_rate']} |")
    lat = metrics["latency"]
    lines.append(f"| 平均延迟(ms) | {lat['avg_ms']} |")
    lines.append(f"| P50延迟(ms) | {lat['p50_ms']} |")
    lines.append(f"| P95延迟(ms) | {lat['p95_ms']} |")
    lines.append("")
    lines.append(f"> 忠实度说明：{metrics['faithfulness_note']}")
    lines.append("")
    lines.append("## 逐样本（脱敏）\n")
    lines.append("| id | 类别 | 模型 | 引用数 | 延迟(ms) | 忠实启发 | 失败类型 |")
    lines.append("| --- | --- | --- | --- | --- | --- | --- |")
    for s in samples:
        lines.append(
            f"| {s.get('id')} | {s.get('category')} | {s.get('model') or '-'} | "
            f"{s.get('referenceCount') or 0} | {s.get('latency_ms')} | "
            f"{'是' if s.get('faithfulness_heuristic') else ('否' if s.get('faithfulness_heuristic') is False else '-')} | "
            f"{s.get('failure_type') or '-'} |"
        )
    lines.append("")
    lines.append("> 说明：生成评估独立于检索评估，调用 /api/rag/ask 触发服务端真实 Chat。")
    lines.append("> 答案与引用来源（不含原文）见 `generation_samples.json`。")
    return "\n".join(lines) + "\n"


if __name__ == "__main__":
    sys.exit(main())
