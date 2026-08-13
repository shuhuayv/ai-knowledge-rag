package com.shuhuayv.rag.dedup;

import com.shuhuayv.rag.entity.KbDocument;

import java.util.List;

/**
 * 单个 active 重复组的治理计划。
 *
 * @param candidates 组内全部候选（active 行）
 * @param winner     canonical 文档（selector 选出的唯一 winner）
 * @param duplicates 其余全部 duplicate（将被软删并写入 canonical_document_id / dedup_batch）
 */
public record DedupGroup(List<KbDocument> candidates, KbDocument winner, List<KbDocument> duplicates) {
}
