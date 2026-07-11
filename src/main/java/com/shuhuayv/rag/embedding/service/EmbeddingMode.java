package com.shuhuayv.rag.embedding.service;

/**
 * Embedding 模式枚举。
 *
 * <p>MOCK：本地伪向量（SHA-256），无需 API Key，384 维，仅用于工程闭环验证。
 * REAL：真实 Embedding API（如智谱 embedding-3），需要 API Key，1024 维，具备真实语义。</p>
 */
public enum EmbeddingMode {

    /** 本地 Mock 伪向量，无网络、无 Key。 */
    MOCK,

    /** 真实 Embedding API，需要 API Key。 */
    REAL
}
