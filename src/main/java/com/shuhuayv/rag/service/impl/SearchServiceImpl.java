package com.shuhuayv.rag.service.impl;

import com.shuhuayv.rag.dto.SearchResponse;
import com.shuhuayv.rag.dto.SearchResultItem;
import com.shuhuayv.rag.embedding.service.EmbeddingService;
import com.shuhuayv.rag.service.KbDocumentService;
import com.shuhuayv.rag.service.SearchService;
import com.shuhuayv.rag.vector.service.CollectionNameResolver;
import com.shuhuayv.rag.vector.service.QdrantVectorService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 语义检索服务（D10：Search/Ask soft-delete 安全层）。
 *
 * <p>关键增强：
 * <ul>
 *   <li>Collection 来源统一走 {@link CollectionNameResolver}，与索引保持一致（Mock/Real 隔离）。</li>
 *   <li>按 {@code rag.retrieval.min-score} 过滤：仅返回 {@code score >= minScore} 的结果。</li>
 *   <li><b>Overfetch</b>：请求 topK=K，内部候选数 {@code candidateTopK =
 *       min(overfetchMaximum, max(K, K * overfetchMultiplier))}，为 active filter 预留余量；
 *       multiplier 受控默认（3），maximum 受控默认（50），禁止无限 overfetch。</li>
 *   <li><b>Active-only 防御层（fail-closed，D10 安全层）</b>：最终返回前对 unique document IDs
 *       做<b>一次</b>批量 {@link KbDocumentService#findActiveDocumentIds} 校验（禁止 N+1）。
 *       任何 <b>{@code documentId == null}</b>（不可信 / Qdrant 脏数据）、<b>未知 id</b> 或
 *       <b>已软删（is_deleted != 0）</b> 的命中项都<b>严格 fail-closed 过滤</b>，
 *       <b>绝不</b>进入 Search API / RAG references / prompt / context；过滤后仍尽可能补足 topK。</li>
 * </ul>
 * </p>
 */
@Slf4j
@Service
public class SearchServiceImpl implements SearchService {

    private final EmbeddingService embeddingService;
    private final QdrantVectorService qdrantVectorService;
    private final CollectionNameResolver collectionNameResolver;
    private final KbDocumentService kbDocumentService;
    private final double minScore;
    private final int overfetchMultiplier;
    private final int overfetchMaximum;

    public SearchServiceImpl(EmbeddingService embeddingService,
                             QdrantVectorService qdrantVectorService,
                             CollectionNameResolver collectionNameResolver,
                             KbDocumentService kbDocumentService,
                             @Value("${rag.retrieval.min-score:0.0}") double minScore,
                             @Value("${rag.retrieval.overfetch-multiplier:3}") int overfetchMultiplier,
                             @Value("${rag.retrieval.overfetch-maximum:50}") int overfetchMaximum) {
        this.embeddingService = embeddingService;
        this.qdrantVectorService = qdrantVectorService;
        this.collectionNameResolver = collectionNameResolver;
        this.kbDocumentService = kbDocumentService;
        this.minScore = minScore;
        this.overfetchMultiplier = Math.max(1, overfetchMultiplier);
        this.overfetchMaximum = Math.max(1, overfetchMaximum);
    }

    @Override
    public SearchResponse search(String query, int topK) {
        long startTime = System.currentTimeMillis();

        List<Float> queryVector = embeddingService.embed(query);
        String collectionName = collectionNameResolver.resolveForCurrentMode(embeddingService,
                com.shuhuayv.rag.service.impl.DocumentIndexServiceImpl.INDEX_VERSION);

        int safeTopK = Math.max(1, topK);
        int candidateTopK = computeCandidateTopK(safeTopK);

        List<SearchResultItem> rawResults;
        try {
            rawResults = qdrantVectorService.search(collectionName, queryVector, candidateTopK);
        } catch (Exception e) {
            long costMs = System.currentTimeMillis() - startTime;
            log.error("Search failed, query={}, costMs={}", query, costMs, e);
            throw new RuntimeException("语义检索失败: " + e.getMessage(), e);
        }

        if (rawResults == null) {
            rawResults = Collections.emptyList();
        }

        int candidateCount = rawResults.size();

        // min-score 过滤（保留既有契约）
        List<SearchResultItem> scoreFiltered = rawResults.stream()
                .filter(r -> r.getScore() != null && r.getScore() >= minScore)
                .collect(Collectors.toList());

        // active-only 防御层：对 unique document IDs 做一次批量校验（禁止 N+1）
        List<Long> uniqueDocumentIds = scoreFiltered.stream()
                .map(SearchResultItem::getDocumentId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        Set<Long> activeDocumentIds = uniqueDocumentIds.isEmpty()
                ? Collections.emptySet()
                : kbDocumentService.findActiveDocumentIds(uniqueDocumentIds);

        int deletedFilteredCount = 0;
        List<SearchResultItem> activeResults = new ArrayList<>();
        for (SearchResultItem item : scoreFiltered) {
            // fail-closed（D10 安全层）：documentId 为 null（不可信脏数据）、未知 id 或已软删
            // （即不在 activeDocumentIds 内）的命中项，一律不放行，绝不进入 Search API /
            // RAG references / prompt / context。任何 null / unknown / inactive 都按不可信过滤。
            if (item.getDocumentId() == null || !activeDocumentIds.contains(item.getDocumentId())) {
                deletedFilteredCount++;
                continue;
            }
            activeResults.add(item);
        }

        // 过滤后仍尽可能补足 topK（overfetch 预留余量在此兑现）
        List<SearchResultItem> finalResults = activeResults.size() > safeTopK
                ? new ArrayList<>(activeResults.subList(0, safeTopK))
                : activeResults;
        int returnedCount = finalResults.size();

        long costMs = System.currentTimeMillis() - startTime;

        log.info("Search completed, query={}, topK={}, candidateTopK={}, candidateCount={}, "
                        + "deletedFilteredCount={}, returnedCount={}, minScore={}, costMs={}",
                query, topK, candidateTopK, candidateCount, deletedFilteredCount, returnedCount, minScore, costMs);

        return SearchResponse.builder()
                .query(query)
                .topK(topK)
                .resultCount(returnedCount)
                .results(finalResults)
                .costMs(costMs)
                .retrievalCandidateCount(candidateCount)
                .retrievalReturnedCount(returnedCount)
                .build();
    }

    /**
     * 计算内部候选数（D10）：{@code candidateTopK = min(overfetchMaximum, max(K, K * multiplier))}。
     *
     * @param topK 请求的 topK（已保证 &gt;= 1）
     * @return 内部候选数
     */
    public int computeCandidateTopK(int topK) {
        int boosted = Math.max(topK, topK * overfetchMultiplier);
        return Math.min(overfetchMaximum, boosted);
    }
}
