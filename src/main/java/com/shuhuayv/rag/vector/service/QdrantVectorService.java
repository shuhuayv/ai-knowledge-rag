package com.shuhuayv.rag.vector.service;

import com.shuhuayv.rag.dto.SearchResultItem;

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
}