package com.shuhuayv.rag.vector.model;

/**
 * Qdrant Collection Snapshot 元数据。
 *
 * <p>对应 Qdrant {@code POST/GET /collections/{c}/snapshots} 响应中的单条 snapshot 记录。
 * 仅承载元数据，<b>不含文件内容</b>——快照文件的下载与校验属于 ops 动作，
 * 由运维侧直接用 HTTP 完成，不在 Java 服务内实现（详见 {@code QdrantSnapshotService} 类注释）。</p>
 *
 * @param name         快照文件名（如 {@code kb_chunks-3378368262218197-2026-08-10-07-12-33.snapshot}）
 * @param creationTime Qdrant 报告的创建时间字符串；缺失时为 {@code null}
 * @param size         快照文件字节数；缺失时为 {@code -1}
 * @param checksum     Qdrant 报告的校验和（通常为 SHA-256）；缺失时为 {@code null}
 */
public record QdrantSnapshotInfo(String name, String creationTime, long size, String checksum) {

    /** size 字段缺失时的占位值。 */
    public static final long SIZE_UNKNOWN = -1L;
}
