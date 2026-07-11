package com.shuhuayv.rag.service;

import com.shuhuayv.rag.dto.RagAskResponse;
import com.shuhuayv.rag.dto.SearchResponse;
import com.shuhuayv.rag.dto.SearchResultItem;
import com.shuhuayv.rag.embedding.service.EmbeddingMode;
import com.shuhuayv.rag.embedding.service.EmbeddingService;
import com.shuhuayv.rag.entity.AiCallLog;
import com.shuhuayv.rag.mapper.AiCallLogMapper;
import com.shuhuayv.rag.service.impl.DocumentIndexServiceImpl;
import com.shuhuayv.rag.service.impl.RagServiceImpl;
import com.shuhuayv.rag.vector.service.CollectionNameResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RagServiceImpl 纯单元测试（Mock 依赖，无 Spring / 无 Chat / 无真实 Embedding）。
 *
 * <p>重点验证：无结果不调用 Chat、返回固定文案、新字段填充、ai_call_log.metadata 写入。</p>
 */
class RagServiceImplTest {

    private final SearchService searchService = mock(SearchService.class);
    private final PromptBuildService promptBuildService = mock(PromptBuildService.class);
    private final ChatModelService chatModelService = mock(ChatModelService.class);
    private final AiCallLogMapper aiCallLogMapper = mock(AiCallLogMapper.class);
    private final EmbeddingService embeddingService = mock(EmbeddingService.class);
    private final CollectionNameResolver resolver = mock(CollectionNameResolver.class);

    private RagServiceImpl service;

    @BeforeEach
    void setUp() {
        when(embeddingService.provider()).thenReturn("zhipu");
        when(embeddingService.model()).thenReturn("embedding-3");
        when(embeddingService.dimensions()).thenReturn(1024);
        when(embeddingService.mode()).thenReturn(EmbeddingMode.REAL);
        when(resolver.resolveForCurrentMode(eq(embeddingService), eq(DocumentIndexServiceImpl.INDEX_VERSION)))
                .thenReturn("kb_chunks_zhipu_embedding_3_1024_v1");
        when(promptBuildService.buildPrompt(anyString(), anyList())).thenReturn("prompt");
        when(chatModelService.generateAnswer(anyString())).thenReturn("基于资料回答");
        when(chatModelService.getProvider()).thenReturn("mock");
        when(chatModelService.getModel()).thenReturn("mock");

        service = new RagServiceImpl(searchService, promptBuildService, chatModelService,
                aiCallLogMapper, embeddingService, resolver, 0.0);
    }

    private SearchResponse searchResponse(List<SearchResultItem> results, int candidate, int returned) {
        return SearchResponse.builder()
                .query("q")
                .topK(5)
                .resultCount(returned)
                .results(results)
                .costMs(10L)
                .retrievalCandidateCount(candidate)
                .retrievalReturnedCount(returned)
                .build();
    }

    private SearchResultItem item(Long docId, Long chunkId, double score) {
        return SearchResultItem.builder()
                .documentId(docId)
                .chunkId(chunkId)
                .chunkIndex(0)
                .content("内容")
                .score(score)
                .collectionName("kb_chunks_zhipu_embedding_3_1024_v1")
                .build();
    }

    @Test
    void shouldNotCallChatWhenNoContextAndReturnFixedAnswer() {
        when(searchService.search(anyString(), anyInt()))
                .thenReturn(searchResponse(List.of(), 0, 0));

        RagAskResponse response = service.ask("知识库里有没有提到 RAG？", 5);

        // 不调用 Chat（仅读取 provider/model 元数据是允许的，但不生成回答）
        verify(chatModelService, never()).generateAnswer(anyString());

        assertThat(response.getAnswer()).contains("未检索到足够相关的内容");
        assertThat(response.getPromptPreview()).isEqualTo("[无有效上下文]");
        assertThat(response.getReferenceCount()).isEqualTo(0);
        assertThat(response.getReferences()).isEmpty();
        assertThat(response.getFallbackUsed()).isFalse();

        // 新字段填充
        assertThat(response.getEmbeddingProvider()).isEqualTo("zhipu");
        assertThat(response.getEmbeddingModel()).isEqualTo("embedding-3");
        assertThat(response.getEmbeddingDimensions()).isEqualTo(1024);
        assertThat(response.getEmbeddingMode()).isEqualTo("REAL");
        assertThat(response.getCollectionName()).isEqualTo("kb_chunks_zhipu_embedding_3_1024_v1");
        assertThat(response.getRetrievalTopK()).isEqualTo(5);
        assertThat(response.getRetrievalMinScore()).isEqualTo(0.0);
        assertThat(response.getRetrievalCandidateCount()).isEqualTo(0);
        assertThat(response.getRetrievalReturnedCount()).isEqualTo(0);
        assertThat(response.getRetrievalQualityNote()).isEqualTo("无有效上下文");
    }

    @Test
    void shouldCallChatAndFillReferencesWhenContextExists() {
        List<SearchResultItem> results = List.of(item(1L, 1L, 0.9), item(2L, 2L, 0.8));
        when(searchService.search(anyString(), anyInt()))
                .thenReturn(searchResponse(results, 2, 2));

        RagAskResponse response = service.ask("RAG 是什么？", 5);

        verify(chatModelService).generateAnswer("prompt");
        assertThat(response.getAnswer()).isEqualTo("基于资料回答");
        assertThat(response.getReferenceCount()).isEqualTo(2);
        assertThat(response.getReferences()).hasSize(2);
        assertThat(response.getFallbackUsed()).isFalse();
        assertThat(response.getRetrievalQualityNote()).isEqualTo("正常");
        assertThat(response.getEmbeddingProvider()).isEqualTo("zhipu");
        assertThat(response.getCollectionName()).isEqualTo("kb_chunks_zhipu_embedding_3_1024_v1");
    }

    @Test
    void shouldWriteNonSensitiveMetadataToAiCallLog() {
        List<SearchResultItem> results = List.of(item(1L, 1L, 0.9));
        when(searchService.search(anyString(), anyInt()))
                .thenReturn(searchResponse(results, 1, 1));

        service.ask("RAG 是什么？", 5);

        var logCaptor = forClass(AiCallLog.class);
        verify(aiCallLogMapper).insert(logCaptor.capture());
        AiCallLog log = logCaptor.getValue();
        assertThat(log.getMetadata()).isNotNull();
        assertThat(log.getMetadata()).contains("\"provider\":\"zhipu\"");
        assertThat(log.getMetadata()).contains("\"model\":\"embedding-3\"");
        assertThat(log.getMetadata()).contains("\"dimensions\":1024");
        assertThat(log.getMetadata()).contains("\"success\":true");
        // 绝不含 Key 痕迹
        assertThat(log.getMetadata().toLowerCase()).doesNotContain("apikey");
        assertThat(log.getMetadata().toLowerCase()).doesNotContain("bearer");
    }
}
