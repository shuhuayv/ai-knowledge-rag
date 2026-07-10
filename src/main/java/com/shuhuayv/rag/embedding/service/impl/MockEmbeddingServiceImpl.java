package com.shuhuayv.rag.embedding.service.impl;

import com.shuhuayv.rag.embedding.service.EmbeddingMode;
import com.shuhuayv.rag.embedding.service.EmbeddingService;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * Mock Embedding 实现：本地 SHA-256 伪向量。
 *
 * <p>特性：
 * <ul>
 *   <li>维度由 {@code app.embedding.dimension} 控制，默认 384。</li>
 *   <li>相同文本稳定输出相同向量（确定性）。</li>
 *   <li>不调用任何外部 API、不需要 API Key。</li>
 *   <li>无真实语义，仅用于工程闭环验证，不可用于生产检索质量评估。</li>
 * </ul>
 * </p>
 *
 * <p>注意：本类不使用 {@code @Service}，由 {@code EmbeddingConfiguration} 通过
 * {@code @Bean} + {@code @ConditionalOnProperty} 装配，与 Zhipu 实现二选一。</p>
 */
@Slf4j
public class MockEmbeddingServiceImpl implements EmbeddingService {

    private final int vectorDimension;

    public MockEmbeddingServiceImpl(int vectorDimension) {
        this.vectorDimension = vectorDimension;
    }

    @Override
    public List<Float> embed(String text) {
        if (text == null || text.isBlank()) {
            return zeroVector();
        }

        byte[] hash = sha256(text);
        List<Float> vector = new ArrayList<>(vectorDimension);

        for (int i = 0; i < vectorDimension; i++) {
            int byteIndex = i % hash.length;
            float value = ((hash[byteIndex] & 0xFF) - 128.0f) / 128.0f;
            vector.add(value);
        }

        float magnitude = (float) Math.sqrt(vector.stream().mapToDouble(v -> v * v).sum());
        if (magnitude > 0) {
            for (int i = 0; i < vector.size(); i++) {
                vector.set(i, vector.get(i) / magnitude);
            }
        }

        return vector;
    }

    @Override
    public List<List<Float>> embedBatch(List<String> texts) {
        if (texts == null) {
            return List.of();
        }
        List<List<Float>> result = new ArrayList<>(texts.size());
        for (String text : texts) {
            result.add(embed(text));
        }
        return result;
    }

    @Override
    public String provider() {
        return "mock";
    }

    @Override
    public String model() {
        return "mock";
    }

    @Override
    public int dimensions() {
        return vectorDimension;
    }

    @Override
    public EmbeddingMode mode() {
        return EmbeddingMode.MOCK;
    }

    @Override
    public boolean real() {
        return false;
    }

    private List<Float> zeroVector() {
        List<Float> vector = new ArrayList<>(vectorDimension);
        for (int i = 0; i < vectorDimension; i++) {
            vector.add(0.0f);
        }
        return vector;
    }

    private byte[] sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
