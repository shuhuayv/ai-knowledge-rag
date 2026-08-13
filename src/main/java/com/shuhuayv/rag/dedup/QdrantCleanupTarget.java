package com.shuhuayv.rag.dedup;

import java.util.List;

/**
 * Qdrant cleanup 目标：某个 collection 内待删除的 exact point ID 列表。
 *
 * @param collectionName collection 名称（必须来自 current managed inventory 才允许清理）
 * @param pointIds       精确 Point ID 列表（UUID 字面量；delete exact IDs，禁止 wildcard/broad delete）
 */
public record QdrantCleanupTarget(String collectionName, List<String> pointIds) {
}
