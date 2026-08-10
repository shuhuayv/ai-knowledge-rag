package com.shuhuayv.rag.vector.service;

import com.shuhuayv.rag.vector.model.QdrantSnapshotInfo;

import java.util.List;

/**
 * Qdrant Collection Snapshot 能力（最小实现：create + list 元数据）。
 *
 * <p><b>为什么独立成一个 Service 而不并入 {@code QdrantVectorService}</b>：
 * {@code QdrantVectorService} 是<b>数据面</b>接口（点的增删查、集合维度），
 * 被 {@code DocumentIndexServiceImpl} 等业务链路直接依赖；snapshot 是<b>运维面</b>能力，
 * 只在备份/回滚 gate 场景使用，两者调用方与变更节奏完全不同。
 * 把 snapshot 塞进数据面接口，会让所有业务侧 Mock 都被迫实现无关方法，
 * 也让"谁能触发备份"这件事失去边界。因此按职责拆开。</p>
 *
 * <p><b>为什么不实现快照文件下载</b>：下载与校验（sha256、落盘、异地保存）是 ops 动作，
 * 涉及大文件流式落盘、磁盘配额、存放路径与保留策略，属于部署环境职责而非应用职责。
 * 在 Java 里做只会引入一段既不被业务调用、又难以在 CI 验证的死代码。
 * 因此本接口<b>只提供元数据</b>，实际下载由运维侧直接用 HTTP
 * （{@code GET /collections/{c}/snapshots/{snapshot_name}}）完成。</p>
 */
public interface QdrantSnapshotService {

    /**
     * 为指定 Collection 创建一个快照。
     *
     * @param collectionName Collection 名称，不可为空
     * @return 新建快照的元数据（含文件名、大小、校验和）
     * @throws IllegalArgumentException 若 {@code collectionName} 为空
     * @throws RuntimeException         Qdrant 返回错误或不可用
     */
    QdrantSnapshotInfo createSnapshot(String collectionName);

    /**
     * 列出指定 Collection 已有的快照元数据。
     *
     * @param collectionName Collection 名称，不可为空
     * @return 快照元数据列表；无快照时返回空列表
     * @throws IllegalArgumentException 若 {@code collectionName} 为空
     * @throws RuntimeException         Qdrant 返回错误或不可用
     */
    List<QdrantSnapshotInfo> listSnapshots(String collectionName);
}
