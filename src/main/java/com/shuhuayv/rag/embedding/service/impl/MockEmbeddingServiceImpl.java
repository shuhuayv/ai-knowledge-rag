package com.shuhuayv.rag.embedding.service.impl;

import com.shuhuayv.rag.embedding.service.EmbeddingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class MockEmbeddingServiceImpl implements EmbeddingService {

    private static final int VECTOR_DIMENSION = 384;

    @Override
    public List<Float> embed(String text) {
        if (text == null || text.isBlank()) {
            return zeroVector();
        }

        byte[] hash = sha256(text);
        List<Float> vector = new ArrayList<>(VECTOR_DIMENSION);

        for (int i = 0; i < VECTOR_DIMENSION; i++) {
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

    private List<Float> zeroVector() {
        List<Float> vector = new ArrayList<>(VECTOR_DIMENSION);
        for (int i = 0; i < VECTOR_DIMENSION; i++) {
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