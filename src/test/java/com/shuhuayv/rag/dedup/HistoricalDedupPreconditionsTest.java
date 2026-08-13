package com.shuhuayv.rag.dedup;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.shuhuayv.rag.entity.KbChunk;
import com.shuhuayv.rag.entity.KbDocument;
import com.shuhuayv.rag.entity.KbVectorRecord;
import com.shuhuayv.rag.mapper.KbChunkMapper;
import com.shuhuayv.rag.mapper.KbDocumentMapper;
import com.shuhuayv.rag.mapper.KbVectorRecordMapper;
import com.shuhuayv.rag.vector.service.QdrantSnapshotService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * J 组测试：PR-3 fail-closed preconditions。
 *
 * <p>纯 Mock，不连真实 DB（REAL_DATABASE_WRITE_FROM_TESTS=NO）。</p>
 */
class HistoricalDedupPreconditionsTest {

    private KbDocumentMapper kbDocumentMapper;
    private KbChunkMapper kbChunkMapper;
    private KbVectorRecordMapper kbVectorRecordMapper;
    private HistoricalDedupTransactionExecutor transactionExecutor;
    private QdrantCleanupService qdrantCleanupService;
    private QdrantSnapshotService qdrantSnapshotService;
    private HistoricalDedupServiceImpl service;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), KbDocument.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), KbChunk.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), KbVectorRecord.class);

        kbDocumentMapper = mock(KbDocumentMapper.class);
        kbChunkMapper = mock(KbChunkMapper.class);
        kbVectorRecordMapper = mock(KbVectorRecordMapper.class);
        transactionExecutor = mock(HistoricalDedupTransactionExecutor.class);
        qdrantCleanupService = mock(QdrantCleanupService.class);
        qdrantSnapshotService = mock(QdrantSnapshotService.class);

        // 默认满足 ①②③
        when(kbDocumentMapper.countM1IdentityColumns()).thenReturn(4);
        when(kbDocumentMapper.countUniqueIndexOnContentIdentity()).thenReturn(0);
        when(kbDocumentMapper.countActiveNullHash()).thenReturn(0L);
        when(kbDocumentMapper.selectCount(any(Wrapper.class))).thenReturn(0L);

        service = new HistoricalDedupServiceImpl(kbDocumentMapper, kbChunkMapper, kbVectorRecordMapper,
                new CanonicalDocumentSelector(), new DedupBatchIdGenerator(), transactionExecutor,
                qdrantCleanupService, qdrantSnapshotService, false);
    }

    private static KbDocument doc(Long id, String status, String hash, LocalDateTime createdAt) {
        KbDocument d = new KbDocument();
        d.setId(id);
        d.setStatus(status);
        d.setContentSha256(hash);
        d.setCreatedAt(createdAt);
        d.setIsDeleted(0L);
        return d;
    }

    @Test
    void j1_m1ColumnsMissingHardFail() {
        when(kbDocumentMapper.countM1IdentityColumns()).thenReturn(3);

        assertThatThrownBy(() -> service.executeDedup(HistoricalDedupCommand.dryRunCommand()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("M1 四列必须存在");
    }

    @Test
    void j2_m2IndexExistsHardFail() {
        when(kbDocumentMapper.countUniqueIndexOnContentIdentity()).thenReturn(1);

        assertThatThrownBy(() -> service.executeDedup(HistoricalDedupCommand.dryRunCommand()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("M2 唯一索引");
    }

    @Test
    void j3_activeNullHashGreaterThanZeroHardFail() {
        when(kbDocumentMapper.countActiveNullHash()).thenReturn(2L);

        assertThatThrownBy(() -> service.executeDedup(HistoricalDedupCommand.dryRunCommand()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("active NULL hash");
    }

    @Test
    void j4_partialDedupStateHardFail() {
        // 同 hash 组内某行已有 canonical_document_id → 已治理/partial → 禁止覆盖
        KbDocument governed = doc(2L, "INDEXED", "hash-A", LocalDateTime.of(2026, 1, 1, 0, 0));
        governed.setCanonicalDocumentId(1L);
        when(kbDocumentMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(doc(1L, "INDEXED", "hash-A", LocalDateTime.of(2026, 1, 2, 0, 0)), governed));
        when(kbChunkMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        when(kbVectorRecordMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        assertThatThrownBy(() -> service.executeDedup(HistoricalDedupCommand.dryRunCommand()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("禁止覆盖");
    }

    @Test
    void j5_vectorInventoryAnomalyHardFail() {
        // 同 hash 组：doc1 chunk=2 vector=3 → vector > chunk → anomaly
        when(kbDocumentMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(
                        doc(1L, "INDEXED", "hash-A", LocalDateTime.of(2026, 1, 1, 0, 0)),
                        doc(2L, "INDEXED", "hash-A", LocalDateTime.of(2026, 1, 2, 0, 0))));
        when(kbChunkMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                chunk(1L, 10L), chunk(1L, 11L)));
        when(kbVectorRecordMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                vector(1L, 10L), vector(1L, 11L), vector(1L, 12L)));

        assertThatThrownBy(() -> service.executeDedup(HistoricalDedupCommand.dryRunCommand()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("vector inventory anomaly");
    }

    @Test
    void j6_sameChunkDuplicateVectorRecordHardFail() {
        when(kbDocumentMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(
                        doc(1L, "INDEXED", "hash-A", LocalDateTime.of(2026, 1, 1, 0, 0)),
                        doc(2L, "INDEXED", "hash-A", LocalDateTime.of(2026, 1, 2, 0, 0))));
        when(kbChunkMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                chunk(1L, 10L), chunk(1L, 11L)));
        when(kbVectorRecordMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                vector(1L, 10L), vector(1L, 10L), vector(1L, 11L))); // chunk 10 重复

        assertThatThrownBy(() -> service.executeDedup(HistoricalDedupCommand.dryRunCommand()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("同 chunk 异常重复 vector record");
    }

    @Test
    void j7_cleanNoOpWhenNoDuplicateGroups() {
        // 全 singleton，无重复组 → clean no-op，不报错
        when(kbDocumentMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(doc(3L, "INDEXED", "hash-solo", LocalDateTime.of(2026, 1, 1, 0, 0))));

        HistoricalDedupResult result = service.executeDedup(HistoricalDedupCommand.dryRunCommand());

        assertThat(result.groups()).isZero();
        assertThat(result.winners()).isZero();
        assertThat(result.duplicates()).isZero();
        assertThat(result.notes()).anyMatch(note -> note.contains("clean no-op"));
        // 无任何写调用
        verify(transactionExecutor, never()).canonicalize(any(), any());
    }

    private static KbChunk chunk(Long documentId, Long chunkId) {
        KbChunk c = new KbChunk();
        c.setId(chunkId);
        c.setDocumentId(documentId);
        return c;
    }

    private static KbVectorRecord vector(Long documentId, Long chunkId) {
        KbVectorRecord v = new KbVectorRecord();
        v.setDocumentId(documentId);
        v.setChunkId(chunkId);
        v.setQdrantPointId("550e8400-e29b-41d4-a716-446655440000");
        v.setCollectionName("kb_chunks_zhipu_embedding_3_1024_v1");
        return v;
    }
}
