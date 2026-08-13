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
import java.util.Set;

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
 * <p>重点验证 min-score 过滤与 candidate/returned 计数。
 * 自 PR-3 起 SearchServiceImpl 增加 active-only 防御层（D10），
 * 测试中 stub {@link KbDocumentService#findActiveDocumentIds} 返回全部 id（默认全部 active）。</p>
 */
class SearchServiceImplTest {

    private final EmbeddingService embeddingService = mock(EmbeddingService.class);
    private final QdrantVectorService qdrantVectorService = mock(QdrantVectorService.class);
    private final CollectionNameResolver resolver = mock(CollectionNameResolver.class);
    private final KbDocumentService kbDocumentService = mock(KbDocumentService.class);

    private SearchServiceImpl serviceWithMinScore(double minScore) {
        when(embeddingService.embed(anyString())).thenReturn(List.of(0.1f));
        when(embeddingService.mode()).thenReturn(EmbeddingMode.MOCK);
        when(resolver.resolveForCurrentMode(eq(embeddingService), eq(DocumentIndexServiceImpl.INDEX_VERSION)))
                .thenReturn("kb_chunks");
        when(kbDocumentService.findActiveDocumentIds(any())).thenReturn(Set.of(1L, 2L, 3L));
        return new SearchServiceImpl(embeddingService, qdrantVectorService, resolver,
                kbDocumentService, minScore, 3, 50);
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

    // ==================== fail-closed 回归（PR-3 Final Safety Correction） ====================
    // 主控 AI 二审发现：item.getDocumentId()==null 在旧 fail-open 逻辑下会放行进入
    // SearchResponse / RAG references / prompt / context。以下测试证明修复后严格 fail-closed。

    /**
     * Test A（null documentId）：rawResults 含 item1(documentId=null) + item2(documentId=2)，
     * active lookup -> {2}。最终仅返回 documentId=2；null item 完全不存在。
     */
    @Test
    void nullDocumentIdIsFilteredOutFailClosed() {
        when(embeddingService.embed(anyString())).thenReturn(List.of(0.1f));
        when(embeddingService.mode()).thenReturn(EmbeddingMode.MOCK);
        when(resolver.resolveForCurrentMode(eq(embeddingService), eq(DocumentIndexServiceImpl.INDEX_VERSION)))
                .thenReturn("kb_chunks");
        when(qdrantVectorService.search(eq("kb_chunks"), anyList(), anyInt()))
                .thenReturn(List.of(item(null, 1L, 0.9), item(2L, 2L, 0.9)));
        when(kbDocumentService.findActiveDocumentIds(any())).thenReturn(Set.of(2L));

        SearchServiceImpl service = serviceWithMinScore(0.0);
        SearchResponse response = service.search("查询", 5);

        assertThat(response.getResults()).hasSize(1);
        assertThat(response.getResults())
                .extracting(SearchResultItem::getDocumentId)
                .containsExactly(2L);
        assertThat(response.getResults()).noneMatch(r -> r.getDocumentId() == null);
    }

    /**
     * Test B（all null）：所有命中项 documentId=null，active lookup 无有效 id。
     * 最终 resultCount=0、results empty（不能因无 active IDs 就返回 raw）。
     */
    @Test
    void allNullDocumentIdsReturnEmptyResultsFailClosed() {
        when(embeddingService.embed(anyString())).thenReturn(List.of(0.1f));
        when(embeddingService.mode()).thenReturn(EmbeddingMode.MOCK);
        when(resolver.resolveForCurrentMode(eq(embeddingService), eq(DocumentIndexServiceImpl.INDEX_VERSION)))
                .thenReturn("kb_chunks");
        when(qdrantVectorService.search(eq("kb_chunks"), anyList(), anyInt()))
                .thenReturn(List.of(item(null, 1L, 0.9), item(null, 2L, 0.8), item(null, 3L, 0.7)));
        when(kbDocumentService.findActiveDocumentIds(any())).thenReturn(Set.of());

        SearchServiceImpl service = serviceWithMinScore(0.0);
        SearchResponse response = service.search("查询", 5);

        assertThat(response.getResults()).isEmpty();
        assertThat(response.getRetrievalReturnedCount()).isEqualTo(0);
        assertThat(response.getResultCount()).isEqualTo(0);
    }

    /**
     * Test C（unknown documentId）：documentId=999999 不在 active lookup 内 -> 被过滤。
     */
    @Test
    void unknownDocumentIdIsFilteredOutFailClosed() {
        when(embeddingService.embed(anyString())).thenReturn(List.of(0.1f));
        when(embeddingService.mode()).thenReturn(EmbeddingMode.MOCK);
        when(resolver.resolveForCurrentMode(eq(embeddingService), eq(DocumentIndexServiceImpl.INDEX_VERSION)))
                .thenReturn("kb_chunks");
        when(qdrantVectorService.search(eq("kb_chunks"), anyList(), anyInt()))
                .thenReturn(List.of(item(999999L, 1L, 0.9), item(2L, 2L, 0.8)));
        when(kbDocumentService.findActiveDocumentIds(any())).thenReturn(Set.of(2L));

        SearchServiceImpl service = serviceWithMinScore(0.0);
        SearchResponse response = service.search("查询", 5);

        assertThat(response.getResults()).hasSize(1);
        assertThat(response.getResults())
                .extracting(SearchResultItem::getDocumentId)
                .containsExactly(2L);
        assertThat(response.getResults()).noneMatch(r -> Long.valueOf(999999L).equals(r.getDocumentId()));
    }

    /**
     * Inactive（已软删）documentId：documentId=4 已知但不在 active lookup {1,2,3} 内 -> 被过滤。
     * 证明 soft-deleted 行（is_deleted != 0）绝不进入 Search API / RAG references。
     */
    @Test
    void softDeletedDocumentIdIsFilteredOutFailClosed() {
        when(embeddingService.embed(anyString())).thenReturn(List.of(0.1f));
        when(embeddingService.mode()).thenReturn(EmbeddingMode.MOCK);
        when(resolver.resolveForCurrentMode(eq(embeddingService), eq(DocumentIndexServiceImpl.INDEX_VERSION)))
                .thenReturn("kb_chunks");
        when(qdrantVectorService.search(eq("kb_chunks"), anyList(), anyInt()))
                .thenReturn(List.of(item(4L, 4L, 0.9), item(2L, 2L, 0.8)));
        when(kbDocumentService.findActiveDocumentIds(any())).thenReturn(Set.of(1L, 2L, 3L));

        SearchServiceImpl service = serviceWithMinScore(0.0);
        SearchResponse response = service.search("查询", 5);

        assertThat(response.getResults()).hasSize(1);
        assertThat(response.getResults())
                .extracting(SearchResultItem::getDocumentId)
                .containsExactly(2L);
        assertThat(response.getResults()).noneMatch(r -> Long.valueOf(4L).equals(r.getDocumentId()));
    }
}
