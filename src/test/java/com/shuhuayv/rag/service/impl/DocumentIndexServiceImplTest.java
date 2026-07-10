package com.shuhuayv.rag.service.impl;

import com.shuhuayv.rag.embedding.service.EmbeddingMode;
import com.shuhuayv.rag.embedding.service.EmbeddingService;
import com.shuhuayv.rag.entity.KbChunk;
import com.shuhuayv.rag.entity.KbDocument;
import com.shuhuayv.rag.entity.KbVectorRecord;
import com.shuhuayv.rag.mapper.KbDocumentMapper;
import com.shuhuayv.rag.mapper.KbVectorRecordMapper;
import com.shuhuayv.rag.service.ChunkService;
import com.shuhuayv.rag.vector.service.CollectionNameResolver;
import com.shuhuayv.rag.vector.service.QdrantVectorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DocumentIndexServiceImpl 纯单元测试（Mock 依赖，无 Spring / 无 Qdrant / 无真实 Embedding）。
 *
 * <p>重点验证：
 * <ul>
 *   <li>buildPointId 生成确定性 UUID v3（nameUUIDFromBytes），符合 Qdrant Point ID 规范
 *       （不再使用非法的 {@code documentId + "_" + chunkId + "_" + indexVersion} 形式）。</li>
 *   <li>indexDocument 全链路写入合法 UUID pointId 及 Embedding 元数据。</li>
 * </ul>
 * </p>
 */
class DocumentIndexServiceImplTest {

    private KbDocumentMapper kbDocumentMapper;
    private KbVectorRecordMapper kbVectorRecordMapper;
    private ChunkService chunkService;
    private EmbeddingService embeddingService;
    private QdrantVectorService qdrantVectorService;
    private CollectionNameResolver resolver;
    private DocumentIndexServiceImpl service;

    private final List<Float> fakeVector = List.of(0.1f, 0.2f, 0.3f);

    @BeforeEach
    void setUp() {
        kbDocumentMapper = mock(KbDocumentMapper.class);
        kbVectorRecordMapper = mock(KbVectorRecordMapper.class);
        chunkService = mock(ChunkService.class);
        embeddingService = mock(EmbeddingService.class);
        qdrantVectorService = mock(QdrantVectorService.class);
        resolver = mock(CollectionNameResolver.class);

        when(embeddingService.provider()).thenReturn("zhipu");
        when(embeddingService.model()).thenReturn("embedding-3");
        when(embeddingService.dimensions()).thenReturn(1024);
        when(embeddingService.mode()).thenReturn(EmbeddingMode.REAL);
        when(resolver.resolveForCurrentMode(eq(embeddingService), eq(DocumentIndexServiceImpl.INDEX_VERSION)))
                .thenReturn("kb_chunks_zhipu_embedding_3_1024_v1");

        service = new DocumentIndexServiceImpl(kbDocumentMapper, kbVectorRecordMapper,
                chunkService, embeddingService, qdrantVectorService, resolver);
    }

    // ---------- buildPointId 回归测试（情况 C：Qdrant Point ID 非法根因修复）----------

    /** 复算期望的确定性 UUID，与实现保持一致的命名规则。 */
    private static String expectedPointId(Long documentId, Long chunkId, String version) {
        String source = documentId + ":" + chunkId + ":" + version;
        return UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8)).toString();
    }

    @Test
    void shouldGenerateValidUuidFormat() {
        String pointId = service.buildPointId(1L, 10L, "v1");
        assertThat(pointId).matches("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    }

    @Test
    void shouldBeDeterministicSameInputSameUuid() {
        String first = service.buildPointId(1L, 10L, "v1");
        String second = service.buildPointId(1L, 10L, "v1");
        assertThat(first).isEqualTo(second);
    }

    @Test
    void shouldBeIdempotentOnReindex() {
        String first = service.buildPointId(1L, 10L, "v1");
        String second = service.buildPointId(1L, 10L, "v1");
        assertThat(first).isEqualTo(second);
    }

    @Test
    void shouldDifferByChunkId() {
        String a = service.buildPointId(1L, 10L, "v1");
        String b = service.buildPointId(1L, 11L, "v1");
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void shouldDifferByDocumentId() {
        String a = service.buildPointId(1L, 10L, "v1");
        String b = service.buildPointId(2L, 10L, "v1");
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void shouldDifferByIndexVersion() {
        String a = service.buildPointId(1L, 10L, "v1");
        String b = service.buildPointId(1L, 10L, "v2");
        assertThat(a).isNotEqualTo(b);
    }

    // ---------- indexDocument 全链路：写入合法 UUID pointId + 元数据 ----------

    private KbDocument aDocument(Long id) {
        KbDocument doc = new KbDocument();
        doc.setId(id);
        doc.setFileName("doc.txt");
        doc.setStatus("PARSED");
        return doc;
    }

    private List<KbChunk> twoChunks(Long docId) {
        KbChunk c1 = new KbChunk();
        c1.setId(10L);
        c1.setDocumentId(docId);
        c1.setContent("RAG 是什么");
        c1.setChunkIndex(0);
        KbChunk c2 = new KbChunk();
        c2.setId(11L);
        c2.setDocumentId(docId);
        c2.setContent("向量数据库");
        c2.setChunkIndex(1);
        return List.of(c1, c2);
    }

    @Test
    void shouldUseResolverAndWriteValidUuidPointIdAndMetadata() {
        KbDocument doc = aDocument(1L);
        when(kbDocumentMapper.selectById(1L)).thenReturn(doc);
        when(chunkService.getChunksByDocumentId(1L)).thenReturn(twoChunks(1L));
        when(embeddingService.embed(anyString())).thenReturn(fakeVector);
        when(kbVectorRecordMapper.insert(any(KbVectorRecord.class))).thenReturn(1);
        when(kbDocumentMapper.updateById(any(KbDocument.class))).thenReturn(1);

        service.indexDocument(1L);

        // 合法 UUID pointId（确定性，符合 Qdrant Point ID 规范）
        var pointIdCaptor = forClass(String.class);
        verify(qdrantVectorService, times(2))
                .upsertPoint(anyString(), pointIdCaptor.capture(), any(), any());
        assertThat(pointIdCaptor.getAllValues())
                .containsExactlyInAnyOrder(expectedPointId(1L, 10L, "v1"), expectedPointId(1L, 11L, "v1"));

        // kb_vector_record 元数据写入
        var recordCaptor = forClass(KbVectorRecord.class);
        verify(kbVectorRecordMapper, times(2)).insert(recordCaptor.capture());
        for (KbVectorRecord r : recordCaptor.getAllValues()) {
            assertThat(r.getEmbeddingProvider()).isEqualTo("zhipu");
            assertThat(r.getEmbeddingModel()).isEqualTo("embedding-3");
            assertThat(r.getEmbeddingDimensions()).isEqualTo(1024);
            assertThat(r.getIndexVersion()).isEqualTo("v1");
            assertThat(r.getCollectionName()).isEqualTo("kb_chunks_zhipu_embedding_3_1024_v1");
            assertThat(r.getStatus()).isEqualTo("INDEXED");
        }

        // kb_document 元数据写入
        var docCaptor = forClass(KbDocument.class);
        verify(kbDocumentMapper).updateById(docCaptor.capture());
        KbDocument updated = docCaptor.getValue();
        assertThat(updated.getEmbeddingProvider()).isEqualTo("zhipu");
        assertThat(updated.getEmbeddingModel()).isEqualTo("embedding-3");
        assertThat(updated.getEmbeddingDimensions()).isEqualTo(1024);
        assertThat(updated.getVectorCollection()).isEqualTo("kb_chunks_zhipu_embedding_3_1024_v1");
        assertThat(updated.getIndexVersion()).isEqualTo("v1");
        assertThat(updated.getIndexedAt()).isNotNull();
        assertThat(updated.getStatus()).isEqualTo("INDEXED");

        // 维度来源为 embeddingService.dimensions()，而非旧 app.qdrant.dimension
        verify(qdrantVectorService).ensureCollection("kb_chunks_zhipu_embedding_3_1024_v1", 1024);
    }

    @Test
    void shouldBeIdempotentAcrossReindex() {
        KbDocument doc = aDocument(1L);
        when(kbDocumentMapper.selectById(1L)).thenReturn(doc);
        when(chunkService.getChunksByDocumentId(1L)).thenReturn(twoChunks(1L));
        when(embeddingService.embed(anyString())).thenReturn(fakeVector);
        when(kbVectorRecordMapper.insert(any(KbVectorRecord.class))).thenReturn(1);
        when(kbDocumentMapper.updateById(any(KbDocument.class))).thenReturn(1);

        service.indexDocument(1L);
        service.indexDocument(1L);

        // 两次索引，pointId 集合应完全一致（幂等覆盖）
        var pointIdCaptor = forClass(String.class);
        verify(qdrantVectorService, times(4))
                .upsertPoint(anyString(), pointIdCaptor.capture(), any(), any());
        assertThat(pointIdCaptor.getAllValues())
                .containsExactlyInAnyOrder(
                        expectedPointId(1L, 10L, "v1"),
                        expectedPointId(1L, 11L, "v1"),
                        expectedPointId(1L, 10L, "v1"),
                        expectedPointId(1L, 11L, "v1"));
    }
}
