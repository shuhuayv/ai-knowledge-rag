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
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 关键 fixture 测试（第 11 节）：用 fixture 模拟真实 3 组结构，dry-run 验证 canonical 选择。
 *
 * <p><b>HARD FAIL 约束</b>：{@code CURRENT_FIXTURE_CANONICALS} 必须 = 2,6,5，否则 FAIL。
 * 真实结构：</p>
 * <ul>
 *   <li>Group A(1,2,4)：1 FAILED incomplete / 2 INDEXED vector-complete / 4 UPLOADED incomplete → winner 2；</li>
 *   <li>Group B(6,7,9,10)：全 INDEXED 全 complete → winner 6（最早 createdAt / id）；</li>
 *   <li>Group C(5,8)：全 INDEXED 全 complete → winner 5。</li>
 * </ul>
 *
 * <p>全部 Mock，不连真实 DB / Qdrant（REAL_DATABASE_WRITE_FROM_TESTS=NO /
 * REAL_QDRANT_WRITE_FROM_TESTS=NO / REAL_PR3_EXECUTION_TRIGGERED=NO）。</p>
 */
class HistoricalDedupServiceImplTest {

    private static final String HASH_A = "hash-A";
    private static final String HASH_B = "hash-B";
    private static final String HASH_C = "hash-C";
    private static final String HASH_SINGLETON = "hash-singleton";

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

        when(kbDocumentMapper.countM1IdentityColumns()).thenReturn(4);
        when(kbDocumentMapper.countUniqueIndexOnContentIdentity()).thenReturn(0);
        when(kbDocumentMapper.countActiveNullHash()).thenReturn(0L);
        when(kbDocumentMapper.selectCount(any(Wrapper.class))).thenReturn(0L);

        service = new HistoricalDedupServiceImpl(kbDocumentMapper, kbChunkMapper, kbVectorRecordMapper,
                new CanonicalDocumentSelector(), new DedupBatchIdGenerator(), transactionExecutor,
                qdrantCleanupService, qdrantSnapshotService, false);
    }

    // ==================== fixture 数据 ====================

    private static KbDocument doc(Long id, String status, String hash, LocalDateTime createdAt) {
        KbDocument d = new KbDocument();
        d.setId(id);
        d.setStatus(status);
        d.setContentSha256(hash);
        d.setCreatedAt(createdAt);
        d.setIsDeleted(0L);
        return d;
    }

    private static KbChunk chunk(Long id, Long documentId) {
        KbChunk c = new KbChunk();
        c.setId(id);
        c.setDocumentId(documentId);
        return c;
    }

    private static KbVectorRecord vector(Long id, Long documentId, Long chunkId, String pointId) {
        KbVectorRecord v = new KbVectorRecord();
        v.setId(id);
        v.setDocumentId(documentId);
        v.setChunkId(chunkId);
        v.setQdrantPointId(pointId);
        v.setCollectionName("kb_chunks_zhipu_embedding_3_1024_v1");
        return v;
    }

    /** 真实结构 fixture：10 个 active 文档（3 组 dup + 1 singleton）。 */
    private void stubRealStructureFixture() {
        // 文档行（is_deleted=0，hash 非空）
        List<KbDocument> documents = new ArrayList<>();
        documents.add(doc(1L, "FAILED", HASH_A, LocalDateTime.of(2026, 1, 1, 0, 0)));
        documents.add(doc(2L, "INDEXED", HASH_A, LocalDateTime.of(2026, 1, 2, 0, 0)));
        documents.add(doc(4L, "UPLOADED", HASH_A, LocalDateTime.of(2026, 1, 3, 0, 0)));
        documents.add(doc(6L, "INDEXED", HASH_B, LocalDateTime.of(2026, 1, 1, 0, 0)));
        documents.add(doc(7L, "INDEXED", HASH_B, LocalDateTime.of(2026, 1, 2, 0, 0)));
        documents.add(doc(9L, "INDEXED", HASH_B, LocalDateTime.of(2026, 1, 3, 0, 0)));
        documents.add(doc(10L, "INDEXED", HASH_B, LocalDateTime.of(2026, 1, 4, 0, 0)));
        documents.add(doc(5L, "INDEXED", HASH_C, LocalDateTime.of(2026, 1, 1, 0, 0)));
        documents.add(doc(8L, "INDEXED", HASH_C, LocalDateTime.of(2026, 1, 2, 0, 0)));
        documents.add(doc(3L, "INDEXED", HASH_SINGLETON, LocalDateTime.of(2026, 1, 1, 0, 0)));
        when(kbDocumentMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(documents);

        // chunks：doc2 → 3 chunks；doc6/7/9/10、doc5/8 → 各 2 chunks；doc1/4/3 → 0
        List<KbChunk> chunks = new ArrayList<>();
        for (long i = 1; i <= 3; i++) {
            chunks.add(chunk(200L + i, 2L));
        }
        for (long d : new long[]{6L, 7L, 9L, 10L, 5L, 8L}) {
            chunks.add(chunk(d * 10 + 1, d));
            chunks.add(chunk(d * 10 + 2, d));
        }
        when(kbChunkMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(chunks);

        // vectors：doc2 → 3；doc6/7/9/10、doc5/8 → 各 2；doc1/4/3 → 0
        List<KbVectorRecord> vectors = new ArrayList<>();
        for (long i = 1; i <= 3; i++) {
            vectors.add(vector(300L + i, 2L, 200L + i, "pt-2-" + i));
        }
        for (long d : new long[]{6L, 7L, 9L, 10L, 5L, 8L}) {
            vectors.add(vector(d * 100 + 1, d, d * 10 + 1, "pt-" + d + "-1"));
            vectors.add(vector(d * 100 + 2, d, d * 10 + 2, "pt-" + d + "-2"));
        }
        when(kbVectorRecordMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(vectors);
    }

    // ==================== fixture：CURRENT_FIXTURE_CANONICALS = 2,6,5（HARD FAIL） ====================

    @Test
    void fixture_canonicalWinnersAreExactlyTwoSixFive() {
        stubRealStructureFixture();

        HistoricalDedupResult result = service.executeDedup(HistoricalDedupCommand.dryRunCommand());

        // HARD FAIL：必须得到 2,6,5
        assertThat(result.canonicalDocumentIds()).containsExactly(2L, 6L, 5L);
        assertThat(result.groups()).isEqualTo(3);
        assertThat(result.winners()).isEqualTo(3);
        assertThat(result.duplicates()).isEqualTo(6);
        assertThat(result.duplicateDocumentIds()).containsExactly(1L, 4L, 7L, 9L, 10L, 8L);
        assertThat(result.dryRun()).isTrue();
    }

    @Test
    void fixture_dryRunNeverWritesDbOrQdrant() {
        stubRealStructureFixture();

        service.executeDedup(HistoricalDedupCommand.dryRunCommand());

        verify(transactionExecutor, never()).canonicalize(any(), any());
        verify(qdrantCleanupService, never()).cleanupDuplicatePoints(any());
        verify(qdrantSnapshotService, never()).createSnapshot(any());
    }

    // ==================== 真实执行路径（Phase A + Phase B） ====================

    @Test
    void realExecution_phaseAThenPhaseB() {
        stubRealStructureFixture();
        when(transactionExecutor.canonicalize(any(), any())).thenReturn(
                new DedupWriteResult(3, 3, 6, List.of(2L, 6L, 5L), List.of(1L, 4L, 7L, 9L, 10L, 8L)));
        when(qdrantCleanupService.cleanupDuplicatePoints(any()))
                .thenReturn(new QdrantCleanupResult(1, 0, false));

        HistoricalDedupResult result = service.executeDedup(new HistoricalDedupCommand(false, null));

        assertThat(result.dryRun()).isFalse();
        assertThat(result.canonicalDocumentIds()).containsExactly(2L, 6L, 5L);
        assertThat(result.qdrantCleanupPending()).isFalse();
        assertThat(result.qdrantManagedCollectionsCleaned()).isEqualTo(1);
        verify(transactionExecutor).canonicalize(any(), any());
        verify(qdrantCleanupService).cleanupDuplicatePoints(any());
    }

    @Test
    void realExecution_qdrantFailureMarksPendingWithoutRollingBackDb() {
        stubRealStructureFixture();
        when(transactionExecutor.canonicalize(any(), any())).thenReturn(
                new DedupWriteResult(3, 3, 6, List.of(2L, 6L, 5L), List.of(1L, 4L, 7L, 9L, 10L, 8L)));
        org.mockito.Mockito.doThrow(new RuntimeException("qdrant delete failed"))
                .when(qdrantCleanupService).cleanupDuplicatePoints(any());

        HistoricalDedupResult result = service.executeDedup(new HistoricalDedupCommand(false, null));

        // MySQL 成功 + Qdrant 失败 → DB 保持治理状态，标记 PR3_QDRANT_CLEANUP_PENDING，不反向恢复
        assertThat(result.qdrantCleanupPending()).isTrue();
        assertThat(result.notes()).anyMatch(note -> note.contains("PR3_QDRANT_CLEANUP_PENDING"));
        assertThat(result.canonicalDocumentIds()).containsExactly(2L, 6L, 5L);
        verify(transactionExecutor).canonicalize(any(), any());
    }

    @Test
    void realExecution_explicitBatchIdIsValidatedAndUsed() {
        stubRealStructureFixture();
        when(transactionExecutor.canonicalize(any(), any())).thenReturn(
                new DedupWriteResult(3, 3, 6, List.of(2L, 6L, 5L), List.of(1L, 4L, 7L, 9L, 10L, 8L)));
        when(qdrantCleanupService.cleanupDuplicatePoints(any()))
                .thenReturn(new QdrantCleanupResult(1, 0, false));

        HistoricalDedupResult result = service.executeDedup(
                new HistoricalDedupCommand(false, "dedup-20260813-01"));

        assertThat(result.batchId()).isEqualTo("dedup-20260813-01");
    }

    @Test
    void realExecution_invalidExplicitBatchIdHardFails() {
        stubRealStructureFixture();

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        service.executeDedup(new HistoricalDedupCommand(false, "BAD_BATCH")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("batchId 非法");
        verify(transactionExecutor, never()).canonicalize(any(), any());
    }
}
