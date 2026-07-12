#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
评估指标库单元测试（无网络依赖，可离线运行）。

运行：
  python3 evaluation/test_metrics.py
  # 或
  python3 -m unittest evaluation/test_metrics -v
"""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from metrics import (  # noqa: E402
    dig, is_relevant, first_relevant_rank, hit_at_k, mrr,
    percentile, latency_stats, summarize_retrieval,
)


class TestDig(unittest.TestCase):
    def test_flat_nested(self):
        self.assertEqual(dig({"data": {"id": 7}}, "data.id"), 7)

    def test_list_index(self):
        obj = {"data": {"results": [{"documentId": 3}, {"documentId": 9}]}}
        self.assertEqual(dig(obj, "data.results[0].documentId"), 3)
        self.assertEqual(dig(obj, "data.results[1].documentId"), 9)

    def test_missing_key(self):
        self.assertIsNone(dig({"a": {"b": 1}}, "a.c"))
        self.assertIsNone(dig({"a": 1}, "a.b.c"))

    def test_bad_index(self):
        self.assertIsNone(dig({"data": {"results": [1]}}, "data.results[5].x"))

    def test_none_obj(self):
        self.assertIsNone(dig(None, "data.id"))

    def test_empty_path(self):
        self.assertIsNone(dig({"a": 1}, ""))


class TestRelevance(unittest.TestCase):
    def test_source_only(self):
        r = {"source": "company_policy.txt", "content": "x"}
        self.assertTrue(is_relevant(r, {"company_policy.txt"}, []))
        self.assertFalse(is_relevant(r, {"other.txt"}, []))

    def test_keyword_only(self):
        r = {"source": "x", "content": "年假有5天"}
        self.assertTrue(is_relevant(r, set(), ["5天"]))
        self.assertFalse(is_relevant(r, set(), ["股票"]))

    def test_both_empty(self):
        r = {"source": "company_policy.txt", "content": "年假"}
        self.assertFalse(is_relevant(r, set(), []))

    def test_both_present_or(self):
        r = {"source": "company_policy.txt", "content": "年假"}
        self.assertTrue(is_relevant(r, {"company_policy.txt"}, ["年假"]))


class TestRankAndHit(unittest.TestCase):
    def test_first_relevant_rank(self):
        results = [
            {"source": "rag-demo.txt", "content": "a"},
            {"source": "company_policy.txt", "content": "年假5天"},
        ]
        self.assertEqual(first_relevant_rank(results, {"company_policy.txt"}, []), 2)
        self.assertIsNone(first_relevant_rank(results, {"missing.txt"}, []))

    def test_hit_at_k(self):
        self.assertTrue(hit_at_k(1, 1))
        self.assertTrue(hit_at_k(3, 5))
        self.assertFalse(hit_at_k(4, 3))
        self.assertFalse(hit_at_k(None, 5))


class TestMrr(unittest.TestCase):
    def test_mrr(self):
        self.assertAlmostEqual(mrr([1, 2, 3]), (1 + 0.5 + 1 / 3) / 3, places=4)
        self.assertAlmostEqual(mrr([None, None]), 0.0)
        self.assertEqual(mrr([]), 0.0)


class TestPercentile(unittest.TestCase):
    def test_percentile(self):
        self.assertEqual(percentile([1, 2, 3, 4, 5], 50), 3)
        self.assertEqual(percentile([10], 95), 10)
        self.assertEqual(percentile([], 95), 0.0)
        # P95 of 1..100 linear ~ 95
        self.assertAlmostEqual(percentile(list(range(1, 101)), 95), 95, places=0)


class TestLatency(unittest.TestCase):
    def test_latency_stats(self):
        st = latency_stats([100, 200, 300])
        self.assertEqual(st["avg_ms"], 200)
        self.assertEqual(st["p50_ms"], 200)
        self.assertGreater(st["p95_ms"], 200)
        self.assertEqual(latency_stats([])["avg_ms"], 0.0)


class TestSummarize(unittest.TestCase):
    def _sample(self, **kw):
        base = {
            "id": "x", "category": "c", "question": "q", "topK": 5,
            "shouldRetrieve": True, "expectedSources": [], "expectedKeywords": [],
            "result_count": 3, "latency_ms": 10,
        }
        base.update(kw)
        return base

    def test_hit_rates(self):
        samples = [
            self._sample(id="1", rank=1, hit=True),
            self._sample(id="2", rank=3, hit=True),
            self._sample(id="3", rank=None, hit=False),
        ]
        m = summarize_retrieval(samples)
        self.assertEqual(m["total_samples"], 3)
        self.assertEqual(m["retrieval_questions"], 3)
        self.assertAlmostEqual(m["hit@1"], 1 / 3, places=4)
        self.assertAlmostEqual(m["hit@3"], 2 / 3, places=4)
        self.assertAlmostEqual(m["hit@5"], 2 / 3, places=4)
        self.assertAlmostEqual(m["mrr"], (1 + 1 / 3 + 0) / 3, places=4)

    def test_failure_recorded_not_zero(self):
        samples = [
            self._sample(id="1", failure_type="http_500", result_count=0),
            self._sample(id="2", rank=1, hit=True),
        ]
        m = summarize_retrieval(samples)
        self.assertEqual(m["failed_samples"], 1)
        self.assertEqual(m["valid_samples"], 1)
        self.assertAlmostEqual(m["http_parse_error_rate"], 0.5, places=4)
        # 失败样本不应计入命中率分母
        self.assertEqual(m["retrieval_questions"], 1)
        self.assertAlmostEqual(m["hit@1"], 1.0, places=4)

    def test_no_answer_correct(self):
        samples = [
            self._sample(id="1", shouldRetrieve=False, rank=None, hit=False, result_count=3),
            self._sample(id="2", shouldRetrieve=False, rank=1, hit=True, result_count=3),
        ]
        m = summarize_retrieval(samples)
        # 无答案样本：正确 abstain（hit=False）的比例 = 1/2
        self.assertAlmostEqual(m["no_answer_correct_rate"], 0.5, places=4)


if __name__ == "__main__":
    unittest.main(verbosity=2)
