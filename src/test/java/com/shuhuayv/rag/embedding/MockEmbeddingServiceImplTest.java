package com.shuhuayv.rag.embedding;

import com.shuhuayv.rag.embedding.service.EmbeddingMode;
import com.shuhuayv.rag.embedding.service.impl.MockEmbeddingServiceImpl;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MockEmbeddingServiceImpl 纯单元测试（无 Spring 上下文、无网络、无 Key）。
 */
class MockEmbeddingServiceImplTest {

    private final MockEmbeddingServiceImpl service = new MockEmbeddingServiceImpl(384);

    @Test
    void shouldProduceFixedDimension() {
        List<Float> v = service.embed("RAG 是什么？");
        assertThat(v).hasSize(384);
    }

    @Test
    void shouldBeDeterministicForSameInput() {
        List<Float> a = service.embed("企业知识库问答系统");
        List<Float> b = service.embed("企业知识库问答系统");
        assertThat(a).isEqualTo(b);
    }

    @Test
    void shouldDifferentiateDifferentTexts() {
        List<Float> a = service.embed("RAG 检索增强生成");
        List<Float> b = service.embed("红烧肉的做法");
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void shouldReturnZeroVectorForBlankText() {
        List<Float> v = service.embed("");
        assertThat(v).hasSize(384);
        assertThat(v).allSatisfy(f -> assertThat(f).isEqualTo(0.0f));
    }

    @Test
    void shouldReturnZeroVectorForNullText() {
        List<Float> v = service.embed(null);
        assertThat(v).hasSize(384);
        assertThat(v).allSatisfy(f -> assertThat(f).isEqualTo(0.0f));
    }

    @Test
    void shouldWorkWithoutApiKeyAndNetwork() {
        // Mock 实现不应需要任何 API Key 或网络调用
        assertThat(service.provider()).isEqualTo("mock");
        assertThat(service.model()).isEqualTo("mock");
        assertThat(service.dimensions()).isEqualTo(384);
        assertThat(service.mode()).isEqualTo(EmbeddingMode.MOCK);
        assertThat(service.real()).isFalse();
        // 仅本地计算，不应抛异常
        assertThat(service.embedBatch(List.of("a", "b", "c"))).hasSize(3);
    }

    @Test
    void shouldRespectConfiguredDimension() {
        MockEmbeddingServiceImpl s = new MockEmbeddingServiceImpl(128);
        assertThat(s.dimensions()).isEqualTo(128);
        assertThat(s.embed("x")).hasSize(128);
    }
}
