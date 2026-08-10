package com.shuhuayv.rag.vector.model;

/**
 * Qdrant 写操作（delete / upsert 等）的响应结果。
 *
 * <p><b>设计约束（重要）</b>：本对象只承载 Qdrant 真实返回的 {@code operation_id} 与 {@code status}，
 * <b>不包含 deletedCount 之类的"删除条数"字段</b>。原因是 Qdrant 的
 * {@code POST /collections/{c}/points/delete} 响应体<b>不返回受影响点数</b>，
 * 任何在客户端侧推算出来的 "deletedCount" 都是伪造数据，会误导治理逻辑。</p>
 *
 * <p>调用方若需要真实删除条数，必须使用以下可验证流程：
 * <pre>
 *   long before = countPoints(c);            // 或 countPointsByDocumentId
 *   deletePoints(c, ids, true);              // wait=true：本地单节点下返回后状态即已落盘可见
 *   long after  = countPoints(c);
 *   long deleted = before - after;           // 真实删除数（observed delta）
 * </pre>
 * 注意：<b>该 delta 仅在「同一 count 作用域内无并发写」时等于真实删除数</b>；
 * 存在并发写时它只是 observed delta，不能声称是绝对删除数。</p>
 *
 * <p><b>字段来源边界（关键）</b>：
 * <ul>
 *   <li>{@code operationId} / {@code status} 来自 Qdrant 的 HTTP 响应（{@code result.operation_id} / {@code result.status}）；</li>
 *   <li>{@code requestedCount} / {@code acceptedCount} 是<b>客户端侧的已知事实</b>（原始入参与本地 UUID 校验后的有效数），
 *       二者<b>不是</b> Qdrant 返回值，也<b>不是</b> deletedCount。</li>
 * </ul>
 * </p>
 *
 * @param operationId   Qdrant 返回的 {@code result.operation_id}；未发起 HTTP 或响应缺失时为 {@code null}
 * @param status        Qdrant 返回的 {@code result.status}（如 {@code completed} / {@code acknowledged}）；
 *                      未发起 HTTP 时为 {@link #STATUS_SKIPPED}
 * @param requestedCount 调用方<b>原始</b> pointIds 数量（null/empty 列表记为 0）。客户端已知事实，非 Qdrant 返回。
 * @param acceptedCount 通过客户端 UUID 格式校验、实际准备发送给 Qdrant 的<b>去重后</b> ID 数量（全非法时为 0）。
 *                      客户端已知事实，非 Qdrant 返回，亦非 deletedCount。
 */
public record QdrantOperationResult(Long operationId, String status, int requestedCount, int acceptedCount) {

    /** 因入参过滤后为空、未向 Qdrant 发起任何 HTTP 请求时的状态值。 */
    public static final String STATUS_SKIPPED = "skipped";

    /** Qdrant 在 {@code wait=true} 时的典型完成状态值。 */
    public static final String STATUS_COMPLETED = "completed";

    /**
     * 构造"未向 Qdrant 发起 HTTP 请求"的结果（例如 pointIds 过滤后为空、或原始输入全部非法）。
     *
     * @param requestedCount 原始入参数量（null/empty 传 0），用于审计"请求了多少 / 接受了 0"
     * @return operationId 为 null、status 为 {@value #STATUS_SKIPPED}、acceptedCount 为 0 的结果
     */
    public static QdrantOperationResult skipped(int requestedCount) {
        return new QdrantOperationResult(null, STATUS_SKIPPED, requestedCount, 0);
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
     * 判断 Qdrant 是否已报告操作完成（{@code wait=true} 语义下，<b>本地单节点观测</b>表示状态已可见；
     * 多副本 / 多分片可见性未验证）。
     *
     * @return status 为 {@value #STATUS_COMPLETED} 时返回 {@code true}
     */
    public boolean isCompleted() {
        return STATUS_COMPLETED.equals(status);
    }
}
