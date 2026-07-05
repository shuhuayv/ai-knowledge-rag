package com.shuhuayv.rag.embedding.service;

import java.util.List;

public interface EmbeddingService {

    List<Float> embed(String text);
}