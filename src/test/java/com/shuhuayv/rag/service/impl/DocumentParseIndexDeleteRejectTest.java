package com.shuhuayv.rag.service.impl;

import com.shuhuayv.rag.embedding.service.EmbeddingMode;
import com.shuhuayv.rag.embedding.service.EmbeddingService;
import com.shuhuayv.rag.entity.KbDocument;
import com.shuhuayv.rag.mapper.KbDocumentMapper;
import com.shuhuayv.rag.mapper.KbVectorRecordMapper;
import com.shuhuayv.rag.service.ChunkService;
import com.shuhuayv.rag.vector.service.CollectionNameResolver;
import com.shuhuayv.rag.vector.service.QdrantVectorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D 组测试：mutating 接口对 soft-deleted 文档 fail closed（D5）。
 *
 * <p>parse / index 对 {@code is_deleted != 0} 必须拒绝，且不得触碰 chunk / Qdrant。</p>
 */
class DocumentParseIndexDeleteRejectTest {

    private KbDocumentMapper kbDocumentMapper;
    private KbVectorRecordMapper kbVectorRecordMapper;
    private ChunkService chunkService;
    private EmbeddingService embeddingService;
    private QdrantVectorService qdrantVectorService;
    private CollectionNameResolver resolver;

    @BeforeEach
    void setUp() {
        kbDocumentMapper = mock(KbDocumentMapper.class);
        kbVectorRecordMapper = mock(KbVectorRecordMapper.class);
        chunkService = mock(ChunkService.class);
        embeddingService = mock(EmbeddingService.class);
        qdrantVectorService = mock(QdrantVectorService.class);
        resolver = mock(CollectionNameResolver.class);
        when(embeddingService.mode()).thenReturn(EmbeddingMode.MOCK);
    }

    private static KbDocument deletedDoc(Long id) {
        KbDocument d = new KbDocument();
        d.setId(id);
        d.setStatus("INDEXED");
        d.setIsDeleted(id); // 已软删：is_deleted = 自身 id
        return d;
    }

    @Test
    void d1_parseDeletedDocumentRejected() {
        when(kbDocumentMapper.selectById(5L)).thenReturn(deletedDoc(5L));
        DocumentParseServiceImpl parseService = new DocumentParseServiceImpl(kbDocumentMapper);

        assertThatThrownBy(() -> parseService.parseDocument(5L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("禁止解析");
    }

    @Test
    void d2_indexDeletedDocumentRejectedWithoutAnySideEffect() {
        when(kbDocumentMapper.selectById(6L)).thenReturn(deletedDoc(6L));
        DocumentIndexServiceImpl indexService = new DocumentIndexServiceImpl(
                kbDocumentMapper, kbVectorRecordMapper, chunkService, embeddingService,
                qdrantVectorService, resolver);

        assertThatThrownBy(() -> indexService.indexDocument(6L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("禁止向量化");

        // 拒绝后不得触碰 chunk / Qdrant / vector record
        verify(chunkService, never()).getChunksByDocumentId(any());
        verify(qdrantVectorService, never()).ensureCollection(any(), anyInt());
        verify(qdrantVectorService, never()).upsertPoint(any(), any(), any(), any());
        verify(kbVectorRecordMapper, never()).insert(any(com.shuhuayv.rag.entity.KbVectorRecord.class));
    }

    @Test
    void d3_parseMissingDocumentRejected() {
        when(kbDocumentMapper.selectById(99L)).thenReturn(null);
        DocumentParseServiceImpl parseService = new DocumentParseServiceImpl(kbDocumentMapper);

        assertThatThrownBy(() -> parseService.parseDocument(99L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("文档不存在");
    }
}
