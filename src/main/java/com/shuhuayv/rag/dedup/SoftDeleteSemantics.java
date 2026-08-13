package com.shuhuayv.rag.dedup;

/**
 * PR-3 软删语义常量与工具（设计决策 D4）。
 *
 * <p><b>is_deleted 取值规则</b>（硬约束，与 MyBatis-Plus {@code @TableLogic} 完全不同）：</p>
 * <ul>
 *   <li>active = {@code 0}（{@link #ACTIVE_FLAG}）；</li>
 *   <li>soft-deleted = <b>该行自身的 document id</b>（{@link #deletedMarker(Long)}），
 *       而不是统一的 {@code 1}。这样每个 tombstone 天然携带自身 id，便于审计与回溯；</li>
 *   <li><b>禁止</b>使用 {@code is_deleted = 1} 统一 marker（会与 M2 的
 *       {@code UNIQUE(content_sha256, is_deleted)} 语义冲突，导致软删行之间互相撞唯一键）。</li>
 * </ul>
 *
 * <p>本项目<b>刻意不启用 {@code @TableLogic}</b>：MyBatis-Plus 的逻辑删除假定 0/1 常量，
 * 与本语义不符，且会掩盖「软删值为自身 id」这一审计信息。</p>
 */
public final class SoftDeleteSemantics {

    /** active 取值。 */
    public static final long ACTIVE_FLAG = 0L;

    private SoftDeleteSemantics() {
    }

    /**
     * 软删标记值 = 该行自身 document id（D4）。
     *
     * @param documentId 该行自身的 document id，不可为空
     * @return 软删后 {@code is_deleted} 应写入的值
     * @throws IllegalArgumentException 若 {@code documentId} 为空
     */
    public static long deletedMarker(Long documentId) {
        if (documentId == null) {
            throw new IllegalArgumentException("documentId 不能为空（软删标记必须是自身 id）");
        }
        return documentId;
    }

    /**
     * 判断 {@code is_deleted} 是否为 active。
     *
     * @param isDeleted 数据库 {@code is_deleted} 列值
     * @return {@code true} 表示 active；{@code null} 视为非 active（fail-closed）
     */
    public static boolean isActive(Long isDeleted) {
        return isDeleted != null && isDeleted == ACTIVE_FLAG;
    }

    /**
     * 判断 {@code is_deleted} 是否为已软删。
     *
     * @param isDeleted 数据库 {@code is_deleted} 列值
     * @return {@code true} 表示已软删（含 {@code null}，fail-closed）
     */
    public static boolean isDeleted(Long isDeleted) {
        return !isActive(isDeleted);
    }
}
