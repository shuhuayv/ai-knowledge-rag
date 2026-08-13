package com.shuhuayv.rag.dedup;

import java.util.List;

/**
 * PR-3 执行结果（dry-run 与真实执行共用）。
 *
 * @param dryRun                        是否为 dry-run（未发生任何 DB/Qdrant 写入）
 * @param batchId                       本次使用的批次号
 * @param groups                        治理的 active 重复组数
 * @param winners                       canonical 文档数（每组 1 个）
 * @param duplicates                    被软删的 duplicate 文档数
 * @param canonicalDocumentIds          canonical 文档 id 列表（升序按组）
 * @param duplicateDocumentIds          duplicate 文档 id 列表
 * @param qdrantCleanupPending          MySQL 成功后 Qdrant cleanup 失败待重跑标记（PR3_QDRANT_CLEANUP_PENDING）
 * @param qdrantManagedCollectionsCleaned 实际清理的 managed collection 数
 * @param qdrantLegacyCollectionsSkipped  被跳过（非 managed inventory）的 legacy collection 数
 * @param notes                         可读审计备注
 */
public record HistoricalDedupResult(
        boolean dryRun,
        String batchId,
        int groups,
        int winners,
        int duplicates,
        List<Long> canonicalDocumentIds,
        List<Long> duplicateDocumentIds,
        boolean qdrantCleanupPending,
        int qdrantManagedCollectionsCleaned,
        int qdrantLegacyCollectionsSkipped,
        List<String> notes) {

    /** 无 active 重复组时的 clean no-op 结果。 */
    public static HistoricalDedupResult empty(boolean dryRun) {
        return new HistoricalDedupResult(dryRun, null, 0, 0, 0,
                List.of(), List.of(), false, 0, 0,
                List.of("无 active 重复组，clean no-op"));
    }
}
