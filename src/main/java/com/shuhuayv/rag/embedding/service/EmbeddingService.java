package com.shuhuayv.rag.embedding.service;

import java.util.List;

/**
 * Embedding 服务抽象接口。
 *
 * <p>统一封装 Mock 与真实（智谱）两种实现，使索引、检索、RAG 流程无需关心底层来源。
 * 通过 {@link #mode()} / {@link #real()} 区分模式，通过 {@link #provider()} / {@link #model()}
 * / {@link #dimensions()} 暴露透明性元数据，便于诊断与状态上报。</p>
 */
public interface EmbeddingService {

    /**
     * 将单条文本转换为向量。
     *
     * @param text 输入文本（允许为 null 或空串，由实现决定行为，如返回零向量）
     * @return 向量（维度 = {@link #dimensions()}）
     */
    List<Float> embed(String text);

    /**
     * 批量将多条文本转换为向量。
     *
     * <p>真实实现内部会按 batch-size 分批调用 API，并按返回 index 恢复顺序，
     * 调用方拿到的顺序与输入顺序严格一致。</p>
     *
     * @param texts 文本列表
     * @return 与输入一一对应的向量列表
     */
    List<List<Float>> embedBatch(List<String> texts);

    /**
     * @return provider 标识（如 "mock" / "zhipu"）
     */
    String provider();

    /**
     * @return 模型名称（如 "mock" / "embedding-3"）
     */
    String model();

    /**
     * @return 向量维度（mock=384，zhipu=1024）
     */
    int dimensions();

    /**
     * @return 当前模式 {@link EmbeddingMode}
     */
    EmbeddingMode mode();

    /**
     * @return 是否为真实 Embedding（true=真实 API，false=Mock）
     */
    boolean real();
}
