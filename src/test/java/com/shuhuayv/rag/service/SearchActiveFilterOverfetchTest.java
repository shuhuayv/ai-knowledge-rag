package com.shuhuayv.rag.service;

import com.shuhuayv.rag.dto.SearchResponse;
import com.shuhuayv.rag.dto.SearchResultItem;
import com.shuhuayv.rag.embedding.service.EmbeddingMode;
import com.shuhuayv.rag.embedding.service.EmbeddingService;
import com.shuhuayv.rag.service.impl.DocumentIndexServiceImpl;
import com.shuhuayv.rag.service.impl.SearchServiceImpl;
import com.shuhuayv.rag.vector.service.CollectionNameResolver;
import com.shuhuayv.rag.vector.service.QdrantVectorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F + G + H 组测试：Search/Ask soft-delete 防御层（D10）。
 *
 * <p>overfetch + active filter + 批量 active lookup（禁止 N+1）；deleted 不得进入 Search API。</p>
 */
class SearchActiveFilterOverfetchTest {

    private final EmbeddingService embeddingService = mock(EmbeddingService.class);
    private final QdrantVectorService qdrantVectorService = mock(QdrantVectorService.class);
    private final CollectionNameResolver resolver = mock(CollectionNameResolver.class);
    private final KbDocumentService kbDocumentService = mock(KbDocumentService.class);

    private SearchServiceImpl service;

    @BeforeEach
    void setUp() {
        when(embeddingService.embed(anyString())).thenReturn(List.of(0.1f));
        when(embeddingService.mode()).thenReturn(EmbeddingMode.MOCK);
        when(resolver.resolveForCurrentMode(eq(embeddingService), eq(DocumentIndexServiceImpl.INDEX_VERSION)))
                .thenReturn("kb_chunks");
        service = new SearchServiceImpl(embeddingService, qdrantVectorService, resolver,
                kbDocumentService, 0.0, 3, 50);
    }

    private static SearchResultItem item(Long docId, Long chunkId, double score) {
        return SearchResultItem.builder()
                .documentId(docId)
                .chunkId(chunkId)
                .chunkIndex(0)
                .content("content-" + docId + "-" + chunkId)
                .score(score)
                .collectionName("kb_chunks")
                .build();
    }

    // ==================== F：stale point 过滤（active/deleted/active → 只返回 active） ====================

    @Test
    void f1_deletedResultsFilteredOutActiveKept() {
        when(qdrantVectorService.search(eq("kb_chunks"), anyList(), anyInt()))
                .thenReturn(List.of(item(1L, 1L, 0.9), item(2L, 2L, 0.8), item(3L, 3L, 0.7)));
        when(kbDocumentService.findActiveDocumentIds(any())).thenReturn(Set.of(1L, 3L));

        SearchResponse response = service.search("query", 5);

        assertThat(response.getResults()).extracting(SearchResultItem::getDocumentId)
                .containsExactly(1L, 3L);
        // deleted(2) 不得进入最终结果
        assertThat(response.getResults()).extracting(SearchResultItem::getDocumentId)
                .doesNotContain(2L);
        assertThat(response.getRetrievalCandidateCount()).isEqualTo(3);
    }

    // ==================== G：overfetch —— deleted 占早序 rank 仍尽可能补足 active topK ====================

    @Test
    void g1_overfetchFillsActiveTopKWhenDeletedOccupyEarlyRanks() {
        // topK=5, multiplier=3, max=50 → candidateTopK=15
        List<SearchResultItem> raw = new ArrayList<>();
        // 前 5 条是 deleted（doc 100-104）
        for (long i = 0; i < 5; i++) {
            raw.add(item(100L + i, 100L + i, 0.95 - i * 0.01));
        }
        // 后 10 条是 active（doc 1-10）
        for (long i = 1; i <= 10; i++) {
            raw.add(item(i, i, 0.90 - i * 0.01));
        }
        when(qdrantVectorService.search(eq("kb_chunks"), anyList(), anyInt())).thenReturn(raw);
        when(kbDocumentService.findActiveDocumentIds(any()))
                .thenReturn(Set.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L));

        SearchResponse response = service.search("query", 5);

        // 内部必须 overfetch 到 15
        verify(qdrantVectorService).search(eq("kb_chunks"), anyList(), eq(15));
        // 最终仍返回 active top5
        assertThat(response.getResultCount()).isEqualTo(5);
        assertThat(response.getResults()).hasSize(5);
        assertThat(response.getResults()).extracting(SearchResultItem::getDocumentId)
                .containsExactly(1L, 2L, 3L, 4L, 5L);
        assertThat(response.getResults()).extracting(SearchResultItem::getDocumentId)
                .noneMatch(id -> id >= 100L);
    }

    @Test
    void g2_candidateTopKFormula() {
        // topK=5 → min(50, max(5,15)) = 15
        assertThat(service.computeCandidateTopK(5)).isEqualTo(15);
        // topK=20 → min(50, max(20,60)) = 50（受 maximum 上限约束）
        assertThat(service.computeCandidateTopK(20)).isEqualTo(50);
        // topK=1 → min(50, max(1,3)) = 3
        assertThat(service.computeCandidateTopK(1)).isEqualTo(3);
    }

    // ==================== H：批量 active lookup，禁止 N+1 ====================

    @Test
    void h1_batchActiveLookupSingleCallWithAllUniqueIds() {
        when(qdrantVectorService.search(eq("kb_chunks"), anyList(), anyInt()))
                .thenReturn(List.of(
                        item(1L, 1L, 0.9), item(2L, 2L, 0.8),
                        item(3L, 3L, 0.7), item(1L, 5L, 0.6))); // doc1 重复 chunk
        when(kbDocumentService.findActiveDocumentIds(any())).thenReturn(Set.of(1L, 2L, 3L));

        service.search("query", 5);

        // 单次批量调用，且入参为 unique ids（去重后 {1,2,3}）
        ArgumentCaptor<java.util.Collection<Long>> captor = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(kbDocumentService, times(1)).findActiveDocumentIds(captor.capture());
        assertThat(captor.getValue()).containsExactlyInAnyOrder(1L, 2L, 3L);
    }

    @Test
    void h2_noActiveLookupWhenScoreFilteredEmpty() {
        when(qdrantVectorService.search(eq("kb_chunks"), anyList(), anyInt()))
                .thenReturn(List.of(item(1L, 1L, 0.2), item(2L, 2L, 0.1)));
        // minScore 0.5 时 scoreFiltered 为空 → 不触发 active lookup
        SearchServiceImpl strictService = new SearchServiceImpl(embeddingService, qdrantVectorService,
                resolver, kbDocumentService, 0.5, 3, 50);
        when(embeddingService.embed(anyString())).thenReturn(List.of(0.1f));
        when(resolver.resolveForCurrentMode(eq(embeddingService), eq(DocumentIndexServiceImpl.INDEX_VERSION)))
                .thenReturn("kb_chunks");

        SearchResponse response = strictService.search("query", 5);

        assertThat(response.getResults()).isEmpty();
        verify(kbDocumentService, times(0)).findActiveDocumentIds(any());
    }
}
