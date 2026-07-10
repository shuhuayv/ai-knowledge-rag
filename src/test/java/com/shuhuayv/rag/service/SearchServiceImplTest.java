package com.shuhuayv.rag.service;

import com.shuhuayv.rag.dto.SearchResponse;
import com.shuhuayv.rag.dto.SearchResultItem;
import com.shuhuayv.rag.embedding.service.EmbeddingMode;
import com.shuhuayv.rag.embedding.service.EmbeddingService;
import com.shuhuayv.rag.service.impl.DocumentIndexServiceImpl;
import com.shuhuayv.rag.service.impl.SearchServiceImpl;
import com.shuhuayv.rag.vector.service.CollectionNameResolver;
import com.shuhuayv.rag.vector.service.QdrantVectorService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SearchServiceImpl 纯单元测试（Mock 依赖，无 Spring / 无 Qdrant）。
 *
 * <p>重点验证 min-score 过滤与 candidate/returned 计数。</p>
 */
class SearchServiceImplTest {

    private final EmbeddingService embeddingService = mock(EmbeddingService.class);
    private final QdrantVectorService qdrantVectorService = mock(QdrantVectorService.class);
    private final CollectionNameResolver resolver = mock(CollectionNameResolver.class);

    private SearchServiceImpl serviceWithMinScore(double minScore) {
        when(embeddingService.embed(anyString())).thenReturn(List.of(0.1f));
        when(embeddingService.mode()).thenReturn(EmbeddingMode.MOCK);
        when(resolver.resolveForCurrentMode(eq(embeddingService), eq(DocumentIndexServiceImpl.INDEX_VERSION)))
                .thenReturn("kb_chunks");
        return new SearchServiceImpl(embeddingService, qdrantVectorService, resolver, minScore);
    }

    private SearchResultItem item(Long docId, Long chunkId, double score) {
        return SearchResultItem.builder()
                .documentId(docId)
                .chunkId(chunkId)
                .chunkIndex(0)
                .content("content-" + docId)
                .score(score)
                .collectionName("kb_chunks")
                .build();
    }

    @Test
    void shouldFilterByMinScoreAndCountCandidateReturned() {
        SearchServiceImpl service = serviceWithMinScore(0.5);
        when(qdrantVectorService.search(eq("kb_chunks"), anyList(), anyInt()))
                .thenReturn(List.of(
                        item(1L, 1L, 0.9),
                        item(2L, 2L, 0.3),
                        item(3L, 3L, 0.6)));

        SearchResponse response = service.search("查询", 5);

        assertThat(response.getRetrievalCandidateCount()).isEqualTo(3);
        assertThat(response.getRetrievalReturnedCount()).isEqualTo(2);
        assertThat(response.getResults()).hasSize(2);
        assertThat(response.getResults())
                .extracting(SearchResultItem::getScore)
                .allSatisfy(s -> assertThat(s).isGreaterThanOrEqualTo(0.5));
    }

    @Test
    void shouldReturnAllWhenMinScoreIsZero() {
        SearchServiceImpl service = serviceWithMinScore(0.0);
        when(qdrantVectorService.search(eq("kb_chunks"), anyList(), anyInt()))
                .thenReturn(List.of(item(1L, 1L, 0.2), item(2L, 2L, 0.1)));

        SearchResponse response = service.search("查询", 5);

        assertThat(response.getRetrievalCandidateCount()).isEqualTo(2);
        assertThat(response.getRetrievalReturnedCount()).isEqualTo(2);
        assertThat(response.getResults()).hasSize(2);
    }
}
