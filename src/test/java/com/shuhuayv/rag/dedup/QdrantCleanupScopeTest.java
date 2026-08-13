package com.shuhuayv.rag.dedup;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.shuhuayv.rag.entity.KbVectorRecord;
import com.shuhuayv.rag.mapper.KbVectorRecordMapper;
import com.shuhuayv.rag.vector.service.QdrantVectorService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * L 组测试：Qdrant cleanup scope —— 仅 current managed collection / exact IDs；
 * legacy mock kb_chunks 永不自动清理（D9）。
 *
 * <p>纯 Mock，不连真实 Qdrant（REAL_QDRANT_WRITE_FROM_TESTS=NO）。</p>
 */
class QdrantCleanupScopeTest {

    private static final String CURRENT_REAL = "kb_chunks_zhipu_embedding_3_1024_v1";
    private static final String LEGACY_MOCK = "kb_chunks";

    private QdrantVectorService qdrantVectorService;
    private KbVectorRecordMapper kbVectorRecordMapper;
    private QdrantCleanupServiceImpl service;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), KbVectorRecord.class);
        qdrantVectorService = mock(QdrantVectorService.class);
        kbVectorRecordMapper = mock(KbVectorRecordMapper.class);
        service = new QdrantCleanupServiceImpl(qdrantVectorService, kbVectorRecordMapper, CURRENT_REAL);
    }

    @Test
    void l1_onlyManagedCollectionCleanedWithExactIds() {
        List<String> pointIds = List.of(
                "1b4e28ba-2fa1-4d3b-a3f5-ccb692a4d2b7",
                "550e8400-e29b-41d4-a716-446655440000");

        QdrantCleanupResult result = service.cleanupDuplicatePoints(List.of(
                new QdrantCleanupTarget(CURRENT_REAL, pointIds)));

        assertThat(result.managedCollectionsCleaned()).isEqualTo(1);
        assertThat(result.legacyCollectionsSkipped()).isZero();
        verify(qdrantVectorService).deletePoints(eq(CURRENT_REAL), eq(pointIds), eq(true));
    }

    @Test
    void l2_legacyMockCollectionNeverCleanedAutomatically() {
        QdrantCleanupResult result = service.cleanupDuplicatePoints(List.of(
                new QdrantCleanupTarget(LEGACY_MOCK, List.of("550e8400-e29b-41d4-a716-446655440000"))));

        assertThat(result.legacyCollectionsSkipped()).isEqualTo(1);
        assertThat(result.managedCollectionsCleaned()).isZero();
        verify(qdrantVectorService, never()).deletePoints(any(), any(), anyBoolean());
    }

    @Test
    void l3_nullOrEmptyTargetsNoInteraction() {
        assertThat(service.cleanupDuplicatePoints(null).managedCollectionsCleaned()).isZero();
        assertThat(service.cleanupDuplicatePoints(List.of()).managedCollectionsCleaned()).isZero();
        verify(qdrantVectorService, never()).deletePoints(any(), any(), anyBoolean());
    }

    @Test
    void l4_emptyPointIdsInManagedCollectionNoHttp() {
        QdrantCleanupResult result = service.cleanupDuplicatePoints(List.of(
                new QdrantCleanupTarget(CURRENT_REAL, List.of())));

        assertThat(result.managedCollectionsCleaned()).isZero();
        verify(qdrantVectorService, never()).deletePoints(any(), any(), anyBoolean());
    }

    @Test
    void l5_mixedManagedAndLegacyTargets() {
        QdrantCleanupResult result = service.cleanupDuplicatePoints(List.of(
                new QdrantCleanupTarget(CURRENT_REAL, List.of("550e8400-e29b-41d4-a716-446655440000")),
                new QdrantCleanupTarget(LEGACY_MOCK, List.of("1b4e28ba-2fa1-4d3b-a3f5-ccb692a4d2b7"))));

        assertThat(result.managedCollectionsCleaned()).isEqualTo(1);
        assertThat(result.legacyCollectionsSkipped()).isEqualTo(1);
        verify(qdrantVectorService, org.mockito.Mockito.times(1))
                .deletePoints(eq(CURRENT_REAL), any(), eq(true));
    }

    @Test
    void l6_cleanupForDocumentUsesLineageAndSkipsLegacy() {
        when(kbVectorRecordMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                vector(7L, CURRENT_REAL, "550e8400-e29b-41d4-a716-446655440000"),
                vector(7L, CURRENT_REAL, "1b4e28ba-2fa1-4d3b-a3f5-ccb692a4d2b7"),
                // legacy mock 无 lineage（此处即使有指向 kb_chunks 的记录，也会被跳过）
                vector(7L, LEGACY_MOCK, "550e8400-e29b-41d4-a716-446655440001"),
                // 无 collection / 无 pointId 的记录被忽略
                vectorWithoutCollection(7L)));

        QdrantCleanupResult result = service.cleanupForDocument(7L);

        assertThat(result.managedCollectionsCleaned()).isEqualTo(1);
        assertThat(result.legacyCollectionsSkipped()).isEqualTo(1);
        verify(qdrantVectorService).deletePoints(eq(CURRENT_REAL),
                eq(List.of("550e8400-e29b-41d4-a716-446655440000",
                        "1b4e28ba-2fa1-4d3b-a3f5-ccb692a4d2b7")), eq(true));
    }

    @Test
    void l7_cleanupForDocumentNullIdRejected() {
        assertThatThrownBy(() -> service.cleanupForDocument(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("documentId 不能为空");
    }

    @Test
    void l8_cleanupForDocumentNoRecordsIsNoOp() {
        when(kbVectorRecordMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        QdrantCleanupResult result = service.cleanupForDocument(7L);

        assertThat(result.managedCollectionsCleaned()).isZero();
        verify(qdrantVectorService, never()).deletePoints(any(), any(), anyBoolean());
    }

    private static KbVectorRecord vector(Long documentId, String collection, String pointId) {
        KbVectorRecord v = new KbVectorRecord();
        v.setDocumentId(documentId);
        v.setCollectionName(collection);
        v.setQdrantPointId(pointId);
        return v;
    }

    private static KbVectorRecord vectorWithoutCollection(Long documentId) {
        KbVectorRecord v = new KbVectorRecord();
        v.setDocumentId(documentId);
        v.setQdrantPointId(null);
        return v;
    }
}
