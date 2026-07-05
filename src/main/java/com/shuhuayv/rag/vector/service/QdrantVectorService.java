package com.shuhuayv.rag.vector.service;

import java.util.List;
import java.util.Map;

public interface QdrantVectorService {

    void ensureCollection(String collectionName, int vectorDimension);

    void upsertPoint(String collectionName, String pointId, List<Float> vector, Map<String, Object> payload);
}