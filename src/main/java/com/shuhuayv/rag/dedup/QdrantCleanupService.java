package com.shuhuayv.rag.dedup;

import java.util.List;

/**
 * Qdrant 安全补偿式 cleanup（collection-scoped、exact point IDs、幂等重跑）。
 *
 * <p><b>范围硬约束（D9 / L 组测试契约）</b>：</p>
 * <ul>
 *   <li>只允许清理 <b>current managed collections inventory</b>（配置
 *       {@code app.dedup.qdrant-managed-collections}，默认
 *       {@code kb_chunks_zhipu_embedding_3_1024_v1}）；</li>
 *   <li>不在 inventory 中的 collection（如 legacy mock {@code kb_chunks}）<b>自动跳过</b>，
 *       绝不自动清理（PR3_MOCK_LEGACY_POINT_CLEANUP=NO）；</li>
 *   <li>只允许删除 <b>exact point IDs</b>（{@link QdrantCleanupTarget#pointIds()}），
 *       禁止 wildcard / broad delete；</li>
 *   <li>幂等：delete exact IDs 已不存在视为 already-clean/success，非 fatal。</li>
 * </ul>
 */
public interface QdrantCleanupService {

    /**
     * 按 exact point IDs 清理一批目标（collection-scoped）。
     *
     * @param targets 清理目标列表；可为 {@code null} 或空（视为无操作）
     * @return 清理结果（managed 清理数 / legacy 跳过数 / pending 标记）
     */
    QdrantCleanupResult cleanupDuplicatePoints(List<QdrantCleanupTarget> targets);

    /**
     * 按 document id 从其 kb_vector_record lineage 构造 exact-point 清理目标并执行。
     *
     * <p>用于 {@code deleteDocument} 软删后的安全补偿式 Qdrant cleanup。</p>
     *
     * @param documentId 文档 id，不可为空
     * @return 清理结果
     * @throws IllegalArgumentException 若 {@code documentId} 为空
     */
    QdrantCleanupResult cleanupForDocument(Long documentId);
}
