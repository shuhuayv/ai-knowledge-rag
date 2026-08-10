package com.shuhuayv.rag.vector.model;

/**
 * Qdrant 写操作（delete / upsert 等）的响应结果。
 *
 * <p><b>设计约束（重要）</b>：本对象<b>只承载 Qdrant 真实返回的 {@code operation_id} 与 {@code status}</b>，
 * <b>不包含 deletedCount 之类的"删除条数"字段</b>。原因是 Qdrant 的
 * {@code POST /collections/{c}/points/delete} 响应体<b>不返回受影响点数</b>，
 * 任何在客户端侧推算出来的 "deletedCount" 都是伪造数据，会误导治理逻辑。</p>
 *
 * <p>调用方若需要真实删除条数，必须使用以下可验证流程：
 * <pre>
 *   long before = countPoints(c);            // 或 countPointsByDocumentId
 *   deletePoints(c, ids, true);              // wait=true 保证返回后状态已落盘可见
 *   long after  = countPoints(c);
 *   long deleted = before - after;           // 真实删除数
 * </pre>
 * </p>
 *
 * @param operationId Qdrant 返回的 {@code result.operation_id}；未发起 HTTP 或响应缺失时为 {@code null}
 * @param status      Qdrant 返回的 {@code result.status}（如 {@code completed} / {@code acknowledged}）；
 *                    未发起 HTTP 时为 {@link #STATUS_SKIPPED}
 */
public record QdrantOperationResult(Long operationId, String status) {

    /** 因入参过滤后为空、未向 Qdrant 发起任何 HTTP 请求时的状态值。 */
    public static final String STATUS_SKIPPED = "skipped";

    /** Qdrant 在 {@code wait=true} 时的典型完成状态值。 */
    public static final String STATUS_COMPLETED = "completed";

    /**
     * 构造"未发起 HTTP 请求"的结果（例如 pointIds 过滤后为空）。
     *
     * @return operationId 为 null、status 为 {@value #STATUS_SKIPPED} 的结果
     */
    public static QdrantOperationResult skipped() {
        return new QdrantOperationResult(null, STATUS_SKIPPED);
    }

    /**
     * 判断本次操作是否实际向 Qdrant 发起了请求。
     *
     * @return 未发起请求返回 {@code false}
     */
    public boolean isSkipped() {
        return STATUS_SKIPPED.equals(status);
    }

    /**
     * 判断 Qdrant 是否已报告操作完成（{@code wait=true} 语义下表示状态已可见）。
     *
     * @return status 为 {@value #STATUS_COMPLETED} 时返回 {@code true}
     */
    public boolean isCompleted() {
        return STATUS_COMPLETED.equals(status);
    }
}
