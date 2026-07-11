package com.shuhuayv.rag.controller;

import com.shuhuayv.rag.common.ApiResponse;
import com.shuhuayv.rag.embedding.service.EmbeddingMode;
import com.shuhuayv.rag.embedding.service.EmbeddingService;
import com.shuhuayv.rag.service.impl.DocumentIndexServiceImpl;
import com.shuhuayv.rag.vector.service.CollectionNameResolver;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * EmbeddingStatusController 纯单元测试（Mock 依赖，无 Spring 上下文）。
 *
 * <p>重点验证：不返回任何 Key；apiKeyConfigured 仅表示环境变量是否存在。</p>
 */
class EmbeddingStatusControllerTest {

    private final EmbeddingService embeddingService = mock(EmbeddingService.class);
    private final CollectionNameResolver resolver = mock(CollectionNameResolver.class);

    private EmbeddingStatusController spyWithKey(boolean keyConfigured) {
        when(embeddingService.provider()).thenReturn("zhipu");
        when(embeddingService.model()).thenReturn("embedding-3");
        when(embeddingService.dimensions()).thenReturn(1024);
        when(embeddingService.mode()).thenReturn(EmbeddingMode.REAL);
        when(resolver.resolveForCurrentMode(eq(embeddingService), eq(DocumentIndexServiceImpl.INDEX_VERSION)))
                .thenReturn("kb_chunks_zhipu_embedding_3_1024_v1");

        EmbeddingStatusController controller =
                new EmbeddingStatusController(embeddingService, resolver, false);
        EmbeddingStatusController spy = spy(controller);
        doReturn(keyConfigured).when(spy).computeApiKeyConfigured();
        return spy;
    }

    @Test
    void shouldReturnStatusWithoutAnyKeyWhenKeyConfigured() {
        EmbeddingStatusController spy = spyWithKey(true);
        ApiResponse<Map<String, Object>> resp = spy.status();
        Map<String, Object> data = resp.getData();

        assertThat(data.get("provider")).isEqualTo("zhipu");
        assertThat(data.get("model")).isEqualTo("embedding-3");
        assertThat(data.get("dimensions")).isEqualTo(1024);
        assertThat(data.get("mode")).isEqualTo("REAL");
        assertThat(data.get("collectionName")).isEqualTo("kb_chunks_zhipu_embedding_3_1024_v1");
        assertThat(data.get("fallbackEnabled")).isEqualTo(false);
        assertThat(data.get("apiKeyConfigured")).isEqualTo(true);

        // apiKeyConfigured 仅为 true/false 布尔，绝不返回 Key 本身/长度/前缀/后缀
        assertThat(data.get("apiKeyConfigured")).isIn(true, false);
        // 任何返回值都不含 Key 特征（如 sk- / Bearer / 长串密钥）
        data.values().forEach(v -> {
            String s = String.valueOf(v).toLowerCase();
            assertThat(s).doesNotContain("sk-");
            assertThat(s).doesNotContain("bearer");
        });
    }

    @Test
    void shouldReportApiKeyNotConfigured() {
        EmbeddingStatusController spy = spyWithKey(false);
        ApiResponse<Map<String, Object>> resp = spy.status();
        assertThat(resp.getData().get("apiKeyConfigured")).isEqualTo(false);
    }

    @Test
    void shouldReportFallbackEnabledWhenConfigured() {
        when(embeddingService.provider()).thenReturn("mock");
        when(embeddingService.model()).thenReturn("mock");
        when(embeddingService.dimensions()).thenReturn(384);
        when(embeddingService.mode()).thenReturn(EmbeddingMode.MOCK);
        when(resolver.resolveForCurrentMode(eq(embeddingService), eq(DocumentIndexServiceImpl.INDEX_VERSION)))
                .thenReturn("kb_chunks");

        EmbeddingStatusController controller =
                new EmbeddingStatusController(embeddingService, resolver, true);
        EmbeddingStatusController spy = spy(controller);
        doReturn(false).when(spy).computeApiKeyConfigured();

        ApiResponse<Map<String, Object>> resp = spy.status();
        assertThat(resp.getData().get("fallbackEnabled")).isEqualTo(true);
        assertThat(resp.getData().get("collectionName")).isEqualTo("kb_chunks");
    }
}
