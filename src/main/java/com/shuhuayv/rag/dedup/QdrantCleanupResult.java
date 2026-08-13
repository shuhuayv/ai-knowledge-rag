package com.shuhuayv.rag.dedup;

/**
 * Qdrant cleanup 结果。
 *
 * @param managedCollectionsCleaned 实际执行了 exact-point delete 的 managed collection 数
 * @param legacyCollectionsSkipped  被跳过（不在 managed inventory 中，如 legacy mock kb_chunks）的 collection 数
 * @param pending                   cleanup 是否失败待重跑（true = PR3_QDRANT_CLEANUP_PENDING）
 */
public record QdrantCleanupResult(int managedCollectionsCleaned, int legacyCollectionsSkipped, boolean pending) {

    public static QdrantCleanupResult none() {
        return new QdrantCleanupResult(0, 0, false);
    }
}
