#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
RAG 评估公共指标库（纯函数，无网络依赖，便于单元测试）。

设计要点：
- dig(): 通用嵌套路径读取，修复旧脚本 ``d.get("data.id")`` 把 "data.id"
  当成扁平 key 的 bug。支持 "data.results[0].documentId" 这类路径。
- 所有函数对空值/异常输入健壮，绝不抛未捕获异常导致评估中断。
"""
import math
import re

_PATH_RE = re.compile(r"^([^\[]+)?\[(\d+)\]$")


def dig(obj, path):
    """通用嵌套路径读取。

    >>> dig({"data": {"id": 7}}, "data.id")
    7
    >>> dig({"data": {"results": [{"documentId": 3}]}}, "data.results[0].documentId")
    3
    >>> dig({"a": {"b": 1}}, "a.c") is None
    True
    """
    if obj is None or not path:
        return None
    cur = obj
    for seg in path.split("."):
        if cur is None:
            return None
        m = _PATH_RE.match(seg)
        if m:
            key = m.group(1) or ""
            idx = int(m.group(2))
        else:
            key = seg
            idx = None
        if isinstance(cur, dict):
            if key == "":
                return None
            cur = cur.get(key)
        elif isinstance(cur, list):
            if key != "":
                return None
            if idx is None:
                return None
            cur = cur[idx] if idx < len(cur) else None
            continue
        else:
            return None
        if idx is not None and isinstance(cur, list):
            cur = cur[idx] if idx < len(cur) else None
    return cur


def is_relevant(result, expected_sources, expected_keywords):
    """判断单条检索结果是否与预期相关。

    - expected_sources: 期望命中的文件名集合（如 {"company_policy.txt"}）。
      若为空集合，则仅按关键词判定。
    - expected_keywords: 期望命中的关键词列表（内容包含其一即相关）。
      若为空列表，则仅按来源判定。
    两者都为空 -> 视为无预期，不相关。
    """
    if not expected_sources and not expected_keywords:
        return False
    source = (result.get("source") or "").strip()
    content = result.get("content") or ""
    src_hit = bool(expected_sources) and source in expected_sources
    kw_hit = False
    if expected_keywords:
        low = content.lower()
        kw_hit = any(str(k).lower() in low for k in expected_keywords)
    if expected_sources and expected_keywords:
        return src_hit or kw_hit
    return src_hit or kw_hit


def first_relevant_rank(results, expected_sources, expected_keywords):
    """返回首个相关结果的 1-based 排名；无则 None。"""
    for i, r in enumerate(results, start=1):
        if is_relevant(r, expected_sources, expected_keywords):
            return i
    return None


def hit_at_k(rank, k):
    """rank 为 1-based 命中排名；k 为阈值。命中返回 True。"""
    return rank is not None and 1 <= rank <= k


def mrr(ranks):
    """平均倒数排名。ranks 为各样本命中排名（None 视为 0）。"""
    if not ranks:
        return 0.0
    s = 0.0
    for r in ranks:
        s += (1.0 / r) if r else 0.0
    return s / len(ranks)


def percentile(values, p):
    """计算分位数 p（0-100）。空列表返回 0.0。线性插值。"""
    if not values:
        return 0.0
    vs = sorted(values)
    if len(vs) == 1:
        return float(vs[0])
    k = (len(vs) - 1) * (p / 100.0)
    f = math.floor(k)
    c = math.ceil(k)
    if f == c:
        return float(vs[int(k)])
    return vs[f] + (vs[c] - vs[f]) * (k - f)


def latency_stats(latencies_ms):
    """返回平均 / P50 / P95（毫秒）。空列表全 0.0。"""
    if not latencies_ms:
        return {"avg_ms": 0.0, "p50_ms": 0.0, "p95_ms": 0.0}
    return {
        "avg_ms": round(sum(latencies_ms) / len(latencies_ms), 2),
        "p50_ms": round(percentile(latencies_ms, 50), 2),
        "p95_ms": round(percentile(latencies_ms, 95), 2),
    }


def safe_div(numer, denom):
    return (numer / denom) if denom else 0.0


def summarize_retrieval(samples):
    """根据逐样本脱敏记录汇总检索指标。

    samples: list of dict，字段见 retrieval_eval.py 的 per-sample 输出。
    返回指标 dict。
    """
    total = len(samples)
    failures = [s for s in samples if s.get("failure_type")]
    valid = [s for s in samples if not s.get("failure_type")]
    successful = [s for s in valid if (s.get("result_count") or 0) > 0]
    no_result = [s for s in valid if (s.get("result_count") or 0) == 0]

    retrieve_samples = [s for s in valid if s.get("shouldRetrieve")]
    ranks = [s["rank"] for s in retrieve_samples]
    hit1 = sum(1 for r in ranks if hit_at_k(r, 1))
    hit3 = sum(1 for r in ranks if hit_at_k(r, 3))
    hit5 = sum(1 for r in ranks if hit_at_k(r, 5))
    n_ret = len(retrieve_samples)

    # 无答案样本（should_retrieve=False）正确 abstain 的比例
    no_answer = [s for s in valid if not s.get("shouldRetrieve")]
    no_answer_ok = sum(1 for s in no_answer if not s.get("hit"))

    lats = [s["latency_ms"] for s in valid if "latency_ms" in s]
    lst = latency_stats(lats)

    http_parse_err = sum(
        1 for s in samples
        if (s.get("failure_type") or "").startswith(("http_", "parse_", "business_"))
    )

    return {
        "total_samples": total,
        "valid_samples": len(valid),
        "successful_retrievals": len(successful),
        "failed_samples": len(failures),
        "no_result_samples": len(no_result),
        "retrieval_questions": n_ret,
        "hit@1": round(safe_div(hit1, n_ret), 4),
        "hit@3": round(safe_div(hit3, n_ret), 4),
        "hit@5": round(safe_div(hit5, n_ret), 4),
        "mrr": round(mrr(ranks), 4),
        "no_result_rate": round(safe_div(len(no_result), total), 4),
        "http_parse_error_rate": round(safe_div(http_parse_err, total), 4),
        "no_answer_correct_rate": round(safe_div(no_answer_ok, len(no_answer)), 4) if no_answer else None,
        "latency": lst,
    }
