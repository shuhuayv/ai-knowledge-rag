package com.shuhuayv.rag.dedup;

import java.util.List;

/**
 * MySQL 事务写入结果（Phase A）。
 *
 * @param groups       写入的组数
 * @param winners      canonical 行数
 * @param duplicates   被更新的 duplicate 行数
 * @param canonicalIds canonical 文档 id 列表
 * @param duplicateIds duplicate 文档 id 列表
 */
public record DedupWriteResult(int groups, int winners, int duplicates,
                               List<Long> canonicalIds, List<Long> duplicateIds) {
}
