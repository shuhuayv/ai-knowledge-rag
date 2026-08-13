package com.shuhuayv.rag.dedup;

/**
 * 向量完整度（VECTOR_COMPLETENESS）分类，PR-3 canonical governance 的第一优先比较键（D1）。
 *
 * <p>枚举自然顺序即优先级顺序：{@link #COMPLETE} 最高 → {@link #INCOMPLETE} → {@link #VECTOR_INVENTORY_ANOMALY}。
 * {@link #VECTOR_INVENTORY_ANOMALY} 在 canonical selector 中被视为 <b>fail-closed</b> 条件：
 * 一旦出现，PR-3 整体中止，绝不静默选择。</p>
 */
public enum VectorCompleteness {

    /** chunk_count &gt; 0 且 vector_record_count &gt;= chunk_count。 */
    COMPLETE,

    /** chunk_count = 0，或 vector_record_count &lt; chunk_count。 */
    INCOMPLETE,

    /** vector_record_count &gt; chunk_count，或同 chunk 存在异常重复 vector record。 */
    VECTOR_INVENTORY_ANOMALY
}
