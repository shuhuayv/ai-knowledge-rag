package com.shuhuayv.rag.vector.service;

import com.shuhuayv.rag.embedding.service.EmbeddingMode;
import com.shuhuayv.rag.embedding.service.EmbeddingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Qdrant Collection 名称解析器（唯一来源）。
 *
 * <p>作用：
 * <ul>
 *   <li><b>Mock 模式</b>：返回 legacy Collection 名称（默认 {@code kb_chunks}），
 *       保留旧 Mock 数据（384 维），不破坏历史数据。</li>
 *   <li><b>Real 模式</b>：返回隔离的 Collection 名称，例如
 *       {@code kb_chunks_zhipu_embedding_3_1024_v1}，与 Mock 物理隔离，避免维度混用。</li>
 * </ul>
 * </p>
 *
 * <p>命名规则：{@code kb_chunks_${provider}_${model}_${dimensions}_${version}}，其中字母数字外字符
 * 统一转为 {@code _} 并合并连续下划线，全部小写。这样 384（mock）与 1024（real）天然隔离。</p>
 */
@Slf4j
@Component
public class CollectionNameResolver {

    private final String legacyCollectionName;
    private final int mockDimension;

    public CollectionNameResolver(
            @Value("${app.qdrant.collection:kb_chunks}") String legacyCollectionName,
            @Value("${app.embedding.dimension:384}") int mockDimension) {
        this.legacyCollectionName = legacyCollectionName;
        this.mockDimension = mockDimension;
    }

    /**
     * 根据 provider / model / dimensions / version 计算真实模式 Collection 名称。
     *
     * @param provider    provider 标识（如 zhipu）
     * @param model       模型名（如 embedding-3）
     * @param dimensions  向量维度（如 1024）
     * @param version     索引版本（如 v1）
     * @return 规范化后的 Collection 名称
     */
    public String resolve(String provider, String model, int dimensions, String version) {
        String raw = String.format("kb_chunks_%s_%s_%d_%s", provider, model, dimensions, version);
        return sanitize(raw);
    }

    /**
     * 依据当前 EmbeddingService 的模式返回对应 Collection 名称。
     *
     * @param embeddingService 当前装配的 EmbeddingService
     * @param version          索引版本（如 v1）
     * @return Mock 模式返回 legacy 名，Real 模式返回隔离名
     */
    public String resolveForCurrentMode(EmbeddingService embeddingService, String version) {
        if (embeddingService.mode() == EmbeddingMode.MOCK) {
            return legacyCollectionName;
        }
        return resolve(embeddingService.provider(), embeddingService.model(), embeddingService.dimensions(), version);
    }

    /**
     * 规范化名称：小写，非字母数字转 {@code _}，合并连续 {@code _}，去除首尾 {@code _}。
     */
    private String sanitize(String input) {
        if (input == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (char c : input.toLowerCase().toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        // 合并连续下划线
        String merged = sb.toString().replaceAll("_+", "_");
        // 去除首尾下划线
        return merged.replaceAll("^_+|_+$", "");
    }

    /** 暴露 legacy Collection 名称（Mock 模式常量来源）。 */
    public String getLegacyCollectionName() {
        return legacyCollectionName;
    }

    /** 暴露 Mock 维度（用于诊断）。 */
    public int getMockDimension() {
        return mockDimension;
    }
}
