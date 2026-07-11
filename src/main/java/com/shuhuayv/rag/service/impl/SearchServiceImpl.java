package com.shuhuayv.rag.service.impl;

import com.shuhuayv.rag.dto.SearchResponse;
import com.shuhuayv.rag.dto.SearchResultItem;
import com.shuhuayv.rag.embedding.service.EmbeddingService;
import com.shuhuayv.rag.service.SearchService;
import com.shuhuayv.rag.vector.service.CollectionNameResolver;
import com.shuhuayv.rag.vector.service.QdrantVectorService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 语义检索服务。
 *
 * <p>关键增强：
 * <ul>
 *   <li>Collection 来源统一走 {@link CollectionNameResolver}，与索引保持一致（Mock/Real 隔离）。</li>
 *   <li>按 {@code rag.retrieval.min-score} 过滤：仅返回 {@code score >= minScore} 的结果。</li>
 *   <li>记录候选数（过滤前）与返回数（过滤后），写入 {@link SearchResponse} 便于透明诊断。</li>
 * </ul>
 * </p>
 */
@Slf4j
@Service
public class SearchServiceImpl implements SearchService {

    private final EmbeddingService embeddingService;
    private final QdrantVectorService qdrantVectorService;
    private final CollectionNameResolver collectionNameResolver;
    private final double minScore;

    public SearchServiceImpl(EmbeddingService embeddingService,
                             QdrantVectorService qdrantVectorService,
                             CollectionNameResolver collectionNameResolver,
                             @Value("${rag.retrieval.min-score:0.0}") double minScore) {
        this.embeddingService = embeddingService;
        this.qdrantVectorService = qdrantVectorService;
        this.collectionNameResolver = collectionNameResolver;
        this.minScore = minScore;
    }

    @Override
    public SearchResponse search(String query, int topK) {
        long startTime = System.currentTimeMillis();

        List<Float> queryVector = embeddingService.embed(query);
        String collectionName = collectionNameResolver.resolveForCurrentMode(embeddingService,
                com.shuhuayv.rag.service.impl.DocumentIndexServiceImpl.INDEX_VERSION);

        List<SearchResultItem> rawResults;
        try {
            rawResults = qdrantVectorService.search(collectionName, queryVector, topK);
        } catch (Exception e) {
            long costMs = System.currentTimeMillis() - startTime;
            log.error("Search failed, query={}, costMs={}", query, costMs, e);
            throw new RuntimeException("语义检索失败: " + e.getMessage(), e);
        }

        if (rawResults == null) {
            rawResults = Collections.emptyList();
        }

        int candidateCount = rawResults.size();
        List<SearchResultItem> filteredResults = rawResults.stream()
                .filter(r -> r.getScore() != null && r.getScore() >= minScore)
                .collect(Collectors.toList());
        int returnedCount = filteredResults.size();

        long costMs = System.currentTimeMillis() - startTime;

        log.info("Search completed, query={}, topK={}, candidateCount={}, returnedCount={}, minScore={}, costMs={}",
                query, topK, candidateCount, returnedCount, minScore, costMs);

        return SearchResponse.builder()
                .query(query)
                .topK(topK)
                .resultCount(returnedCount)
                .results(filteredResults)
                .costMs(costMs)
                .retrievalCandidateCount(candidateCount)
                .retrievalReturnedCount(returnedCount)
                .build();
    }
}
