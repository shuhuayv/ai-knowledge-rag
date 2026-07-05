package com.shuhuayv.rag.service.impl;

import com.shuhuayv.rag.dto.SearchResponse;
import com.shuhuayv.rag.dto.SearchResultItem;
import com.shuhuayv.rag.embedding.service.EmbeddingService;
import com.shuhuayv.rag.service.SearchService;
import com.shuhuayv.rag.vector.service.QdrantVectorService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

@Slf4j
@Service
public class SearchServiceImpl implements SearchService {

    private final EmbeddingService embeddingService;
    private final QdrantVectorService qdrantVectorService;

    @Value("${app.qdrant.collection:kb_chunks}")
    private String collectionName;

    public SearchServiceImpl(EmbeddingService embeddingService,
                             QdrantVectorService qdrantVectorService) {
        this.embeddingService = embeddingService;
        this.qdrantVectorService = qdrantVectorService;
    }

    @Override
    public SearchResponse search(String query, int topK) {
        long startTime = System.currentTimeMillis();

        List<Float> queryVector = embeddingService.embed(query);

        List<SearchResultItem> results;
        try {
            results = qdrantVectorService.search(collectionName, queryVector, topK);
        } catch (Exception e) {
            long costMs = System.currentTimeMillis() - startTime;
            log.error("Search failed, query={}, costMs={}", query, costMs, e);
            throw new RuntimeException("语义检索失败: " + e.getMessage(), e);
        }

        if (results == null) {
            results = Collections.emptyList();
        }

        long costMs = System.currentTimeMillis() - startTime;

        log.info("Search completed, query={}, topK={}, resultCount={}, costMs={}", query, topK, results.size(), costMs);

        return SearchResponse.builder()
                .query(query)
                .topK(topK)
                .resultCount(results.size())
                .results(results)
                .costMs(costMs)
                .build();
    }
}