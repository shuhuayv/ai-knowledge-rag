package com.shuhuayv.rag.vector;

import com.shuhuayv.rag.embedding.service.EmbeddingMode;
import com.shuhuayv.rag.embedding.service.EmbeddingService;
import com.shuhuayv.rag.vector.service.CollectionNameResolver;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CollectionNameResolver 纯单元测试（无 Spring 上下文）。
 */
class CollectionNameResolverTest {

    private final CollectionNameResolver resolver = new CollectionNameResolver("kb_chunks", 384);

    @Test
    void shouldReturnLegacyNameForMockMode() {
        EmbeddingService mockEmbedding = mock(EmbeddingService.class);
        when(mockEmbedding.mode()).thenReturn(EmbeddingMode.MOCK);

        assertThat(resolver.resolveForCurrentMode(mockEmbedding, "v1")).isEqualTo("kb_chunks");
    }

    @Test
    void shouldReturnIsolatedNameForRealMode() {
        EmbeddingService realEmbedding = mock(EmbeddingService.class);
        when(realEmbedding.mode()).thenReturn(EmbeddingMode.REAL);
        when(realEmbedding.provider()).thenReturn("zhipu");
        when(realEmbedding.model()).thenReturn("embedding-3");
        when(realEmbedding.dimensions()).thenReturn(1024);

        assertThat(resolver.resolveForCurrentMode(realEmbedding, "v1"))
                .isEqualTo("kb_chunks_zhipu_embedding_3_1024_v1");
    }

    @Test
    void shouldIsolate384And1024Collections() {
        EmbeddingService mockEmbedding = mock(EmbeddingService.class);
        when(mockEmbedding.mode()).thenReturn(EmbeddingMode.MOCK);
        EmbeddingService realEmbedding = mock(EmbeddingService.class);
        when(realEmbedding.mode()).thenReturn(EmbeddingMode.REAL);
        when(realEmbedding.provider()).thenReturn("zhipu");
        when(realEmbedding.model()).thenReturn("embedding-3");
        when(realEmbedding.dimensions()).thenReturn(1024);

        String mockCollection = resolver.resolveForCurrentMode(mockEmbedding, "v1");
        String realCollection = resolver.resolveForCurrentMode(realEmbedding, "v1");

        assertThat(mockCollection).isEqualTo("kb_chunks");
        assertThat(realCollection).isEqualTo("kb_chunks_zhipu_embedding_3_1024_v1");
        assertThat(mockCollection).isNotEqualTo(realCollection);
    }

    @Test
    void shouldSanitizeSpecialCharacters() {
        String name = resolver.resolve("Zhi Pu!", "emb-3.0/v2", 1024, "v1");
        assertThat(name).isEqualTo("kb_chunks_zhi_pu_emb_3_0_v2_1024_v1");
    }

    @Test
    void shouldMergeConsecutiveUnderscoresAndLowercase() {
        String name = resolver.resolve("My__Provider", "model.Name", 1024, "v1");
        assertThat(name).isEqualTo("kb_chunks_my_provider_model_name_1024_v1");
    }
}
