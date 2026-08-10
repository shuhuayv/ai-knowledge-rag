package com.shuhuayv.rag.vector.service;

import com.shuhuayv.rag.dto.SearchResultItem;
import com.shuhuayv.rag.vector.model.QdrantOperationResult;
import com.shuhuayv.rag.vector.model.ScrollPage;

import java.util.List;
import java.util.Map;

public interface QdrantVectorService {

    void ensureCollection(String collectionName, int vectorDimension);

    void upsertPoint(String collectionName, String pointId, List<Float> vector, Map<String, Object> payload);

    List<SearchResultItem> search(String collectionName, List<Float> queryVector, int topK);

    /**
     * 读取远端 Collection 的向量维度（size）。
     *
     * @param collectionName Collection 名称
     * @return 向量维度
     * @throws RuntimeException 若 Collection 不存在或读取失败
     */
    int getVectorSize(String collectionName);

    // ==================== Lifecycle 治理能力（PR-1 新增） ====================

    /**
     * 按精确 Point ID 批量删除点。
     *
     * <p><b>Point ID 兼容性契约（硬约束）</b>：历史数据中同时存在
     * <b>UUID v3</b>（{@code UUID.nameUUIDFromBytes}，见 {@code DocumentIndexServiceImpl#buildPointId}）
     * 与 <b>UUID v4</b>（随机）两种 Point ID。本方法<b>对两者一视同仁</b>，
     * 绝不会因为某个 ID "无法被 buildPointId() 复算出来" 就拒绝删除。
     * 入参校验<b>只</b>拒绝三类：{@code null}、空白字符串、不符合 UUID 字面量格式的字符串；
     * 且 UUID 的 version 位<b>不参与</b>校验。</p>
     *
     * <p><b>非法项处理</b>：非法项被<b>跳过并记录 warn 日志</b>，不抛异常，也不会让整批请求被 Qdrant 判为 400。
     * 若过滤后列表为空，则<b>完全不发起 HTTP 请求</b>，直接返回
     * {@link QdrantOperationResult#skipped()}。</p>
     *
     * <p><b>删除条数</b>：返回值只含 {@code operation_id} 与 {@code status}，不含删除条数。
     * 真实删除数请由调用方用 before count → delete(wait=true) → after count 计算。</p>
     *
     * @param collectionName Collection 名称，不可为空
     * @param pointIds       待删除的 Point ID 列表，可为 {@code null} 或含非法项
     * @param wait           是否等待操作落盘可见；治理路径应固定传 {@code true}
     * @return Qdrant 返回的 operationId + status；未发起 HTTP 时返回 {@link QdrantOperationResult#skipped()}
     * @throws IllegalArgumentException 若 {@code collectionName} 为空
     * @throws RuntimeException         Qdrant 返回错误或不可用
     */
    QdrantOperationResult deletePoints(String collectionName, List<String> pointIds, boolean wait);

    /**
     * 按 payload 中的 {@code documentId} 过滤删除该文档的全部点。
     *
     * <p><b>类型契约（硬约束）</b>：过滤条件序列化为 <b>JSON number</b>，即
     * {@code {"match": {"value": 6}}}，<b>绝不是</b> {@code {"match": {"value": "6"}}}。
     * Qdrant 的 match 是强类型匹配，字符串 {@code "6"} 无法命中数值 payload，会静默返回 0 条命中，
     * 从而造成"以为删干净了、实际一条没删"的静默数据不一致。</p>
     *
     * @param collectionName Collection 名称，不可为空
     * @param documentId     文档 ID，<b>不可为 null</b>
     * @param wait           是否等待操作落盘可见；治理路径应固定传 {@code true}
     * @return Qdrant 返回的 operationId + status
     * @throws IllegalArgumentException 若 {@code documentId} 为 {@code null} 或 {@code collectionName} 为空；
     *                                  此时<b>不发起任何 HTTP 请求</b>
     * @throws RuntimeException         Qdrant 返回错误或不可用
     */
    QdrantOperationResult deletePointsByDocumentId(String collectionName, Long documentId, boolean wait);

    /**
     * 精确统计 Collection 中的点总数（{@code exact=true}）。
     *
     * @param collectionName Collection 名称，不可为空
     * @return 点总数
     * @throws IllegalArgumentException 若 {@code collectionName} 为空
     * @throws RuntimeException         Qdrant 返回错误或不可用
     */
    long countPoints(String collectionName);

    /**
     * 精确统计某个 {@code documentId} 在 Collection 中的点数（{@code exact=true}）。
     *
     * <p>过滤条件与 {@link #deletePointsByDocumentId} 使用<b>同一构造逻辑</b>，
     * 保证 {@code documentId} 序列化为 JSON number，从而 count 与 delete 的口径严格一致。</p>
     *
     * @param collectionName Collection 名称，不可为空
     * @param documentId     文档 ID，<b>不可为 null</b>
     * @return 该文档的点数
     * @throws IllegalArgumentException 若 {@code documentId} 为 {@code null} 或 {@code collectionName} 为空；
     *                                  此时<b>不发起任何 HTTP 请求</b>
     * @throws RuntimeException         Qdrant 返回错误或不可用
     */
    long countPointsByDocumentId(String collectionName, Long documentId);

    /**
     * 分页游标遍历 Collection 中的点（单页）。
     *
     * <p>固定使用 {@code with_payload=true} 与 {@code with_vector=false}：
     * 治理/一致性核对只需要 id 与 payload，拉回向量会带来数量级的无谓网络与内存开销。</p>
     *
     * <p>本轮只提供<b>单页契约</b>；是否继续翻页由调用方依据
     * {@link ScrollPage#nextOffset()} 自行决定，maxPages 熔断留给未来的 ConsistencyCheckService。</p>
     *
     * @param collectionName Collection 名称，不可为空
     * @param offset         起始游标；{@code null} 或空白表示从头开始
     * @param limit          单页点数，取值范围 {@code [1, 1000]}
     * @return 单页结果；无数据时返回 {@link ScrollPage#empty()}
     * @throws IllegalArgumentException 若 {@code collectionName} 为空或 {@code limit} 越界
     * @throws RuntimeException         Qdrant 返回错误或不可用
     */
    ScrollPage scrollPoints(String collectionName, String offset, int limit);

    /**
     * 判断 Collection 是否存在。
     *
     * <p><b>只读约束（硬约束）</b>：本方法走 Qdrant 只读端点
     * {@code GET /collections/{c}/exists}，<b>不得</b>用
     * {@link #ensureCollection} 来"顺便"判存在性——后者在 Collection 不存在时会<b>创建</b>它，
     * 属于写副作用，会把一次探测变成一次静默建库。</p>
     *
     * @param collectionName Collection 名称，不可为空
     * @return 存在返回 {@code true}
     * @throws IllegalArgumentException 若 {@code collectionName} 为空
     * @throws RuntimeException         Qdrant 不可用或返回非预期错误
     */
    boolean collectionExists(String collectionName);

    /**
     * 列出 Qdrant 上的 Collection 名称，可按前缀过滤。
     *
     * @param namePrefix 名称前缀；{@code null} 或空白表示不过滤，返回全部
     * @return 升序排序后的 Collection 名称列表；无匹配时返回空列表
     * @throws RuntimeException Qdrant 返回错误或不可用
     */
    List<String> listCollections(String namePrefix);
}
