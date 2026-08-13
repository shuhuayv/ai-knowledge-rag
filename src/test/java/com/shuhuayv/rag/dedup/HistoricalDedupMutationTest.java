package com.shuhuayv.rag.dedup;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.shuhuayv.rag.entity.KbDocument;
import com.shuhuayv.rag.mapper.KbDocumentMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * K 组测试：PR-3 mutation contract（D2/D3/D4）。
 *
 * <p>验证 transaction executor 的 UPDATE 语义：duplicate → is_deleted=self id、
 * canonical_document_id=winner、dedup_batch=batch；canonical 保持 active 且 canonical_document_id NULL；
 * 乐观守卫 affected=1，否则抛异常整体回滚。纯 Mock，不连真实 DB。</p>
 */
class HistoricalDedupMutationTest {

    private KbDocumentMapper kbDocumentMapper;
    private HistoricalDedupTransactionExecutor executor;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), KbDocument.class);
        kbDocumentMapper = mock(KbDocumentMapper.class);
        executor = new HistoricalDedupTransactionExecutor(kbDocumentMapper);
    }

    private static KbDocument doc(Long id, String status, LocalDateTime createdAt) {
        KbDocument d = new KbDocument();
        d.setId(id);
        d.setStatus(status);
        d.setCreatedAt(createdAt);
        d.setIsDeleted(0L);
        return d;
    }

    @Test
    void k1_buildDuplicateUpdateSemantics() {
        KbDocument duplicate = doc(1L, "INDEXED", LocalDateTime.of(2026, 1, 1, 0, 0));
        KbDocument winner = doc(2L, "INDEXED", LocalDateTime.of(2026, 1, 2, 0, 0));
        String batch = "dedup-20260812-01";

        LambdaUpdateWrapper<KbDocument> update =
                HistoricalDedupTransactionExecutor.buildDuplicateUpdate(duplicate, winner, batch);

        // SET：is_deleted / canonical_document_id / dedup_batch
        String sqlSet = update.getSqlSet();
        assertThat(sqlSet).containsIgnoringCase("is_deleted");
        assertThat(sqlSet).containsIgnoringCase("canonical_document_id");
        assertThat(sqlSet).containsIgnoringCase("dedup_batch");
        // WHERE 守卫：id / is_deleted / canonical IS NULL / dedup IS NULL
        String where = update.getExpression().getSqlSegment();
        assertThat(where).containsIgnoringCase("id");
        assertThat(where).containsIgnoringCase("is_deleted");
        assertThat(where).containsIgnoringCase("canonical_document_id IS NULL");
        assertThat(where).containsIgnoringCase("dedup_batch IS NULL");
        // 参数：is_deleted=自身 id、canonical=winner id、batch、守卫 id/active
        Map<String, Object> params = update.getParamNameValuePairs();
        assertThat(params.values()).contains(1L, 2L, batch, 1L, 0L);
    }

    @Test
    void k2_canonicalizeUpdatesAllDuplicatesAndKeepsWinnersUntouched() {
        KbDocument winner = doc(2L, "INDEXED", LocalDateTime.of(2026, 1, 1, 0, 0));
        KbDocument dup1 = doc(1L, "FAILED", LocalDateTime.of(2026, 1, 1, 0, 0));
        KbDocument dup4 = doc(4L, "UPLOADED", LocalDateTime.of(2026, 1, 1, 0, 0));
        when(kbDocumentMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);

        DedupWriteResult result = executor.canonicalize(
                List.of(new DedupGroup(List.of(winner, dup1, dup4), winner, List.of(dup1, dup4))),
                "dedup-20260812-01");

        assertThat(result.groups()).isEqualTo(1);
        assertThat(result.winners()).isEqualTo(1);
        assertThat(result.duplicates()).isEqualTo(2);
        assertThat(result.canonicalIds()).containsExactly(2L);
        assertThat(result.duplicateIds()).containsExactly(1L, 4L);
        verify(kbDocumentMapper, times(2)).update(isNull(), any(LambdaUpdateWrapper.class));
    }

    @Test
    void k3_optimisticGuardFailureThrowsAndRollsBack() {
        KbDocument winner = doc(2L, "INDEXED", LocalDateTime.of(2026, 1, 1, 0, 0));
        KbDocument dup1 = doc(1L, "FAILED", LocalDateTime.of(2026, 1, 1, 0, 0));
        when(kbDocumentMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(0);

        assertThatThrownBy(() -> executor.canonicalize(
                List.of(new DedupGroup(List.of(winner, dup1), winner, List.of(dup1))),
                "dedup-20260812-01"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("乐观守卫失败");
    }

    @Test
    void k4_batchIdGeneratorFormatsAndValidates() {
        DedupBatchIdGenerator generator = new DedupBatchIdGenerator();
        String id = generator.generate(LocalDateTime.of(2026, 8, 12, 10, 0).toLocalDate(), 1);
        assertThat(id).isEqualTo("dedup-20260812-01");
        assertThat(id).hasSizeLessThanOrEqualTo(32);
        assertThat(generator.isValid("dedup-20260812-01")).isTrue();
        assertThat(generator.isValid("dedup-20260812-1")).isFalse();
        assertThat(generator.isValid("dedup-20260812-999")).isFalse();
        assertThat(generator.isValid(null)).isFalse();
        assertThat(generator.isValid("hardcoded-batch")).isFalse();
        assertThatThrownBy(() -> generator.generate(LocalDate.of(2026, 8, 12), 100))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
