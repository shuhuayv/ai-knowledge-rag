package com.shuhuayv.rag.dedup;

import com.shuhuayv.rag.entity.KbDocument;

/**
 * canonical governance 的候选文档及其向量完整度快照。
 *
 * @param document          候选文档（必须是 active 行）
 * @param chunkCount        该文档的 {@code kb_chunk} 行数
 * @param vectorRecordCount 该文档的 {@code kb_vector_record} 行数
 */
public record CanonicalCandidate(KbDocument document, long chunkCount, long vectorRecordCount) {

    /**
     * 计算向量完整度分类（D1 / B 组测试契约）。
     *
     * <ul>
     *   <li>chunk=2/vector=2 → {@link VectorCompleteness#COMPLETE}；</li>
     *   <li>chunk=2/vector=1 → {@link VectorCompleteness#INCOMPLETE}；</li>
     *   <li>chunk=0/vector=0 → {@link VectorCompleteness#INCOMPLETE}（chunk 必须 &gt; 0）；</li>
     *   <li>vector &gt; chunk → {@link VectorCompleteness#VECTOR_INVENTORY_ANOMALY}（fail-closed）。</li>
     * </ul>
     *
     * @return 向量完整度分类
     */
    public VectorCompleteness completeness() {
        if (vectorRecordCount > chunkCount) {
            return VectorCompleteness.VECTOR_INVENTORY_ANOMALY;
        }
        if (chunkCount > 0 && vectorRecordCount >= chunkCount) {
            return VectorCompleteness.COMPLETE;
        }
        return VectorCompleteness.INCOMPLETE;
    }
}
