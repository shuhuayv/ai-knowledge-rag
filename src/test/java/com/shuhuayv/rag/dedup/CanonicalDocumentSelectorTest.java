package com.shuhuayv.rag.dedup;

import com.shuhuayv.rag.entity.KbDocument;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A 组 + B 组测试：canonical governance selector 与 vector completeness。
 *
 * <p>全部为纯逻辑单元测试（Mockito 不连任何 DB/Qdrant）。</p>
 */
class CanonicalDocumentSelectorTest {

    private final CanonicalDocumentSelector selector = new CanonicalDocumentSelector();

    private static KbDocument doc(Long id, String status, LocalDateTime createdAt) {
        KbDocument d = new KbDocument();
        d.setId(id);
        d.setStatus(status);
        d.setCreatedAt(createdAt);
        d.setIsDeleted(0L);
        return d;
    }

    private static CanonicalCandidate candidate(KbDocument d, long chunkCount, long vectorCount) {
        return new CanonicalCandidate(d, chunkCount, vectorCount);
    }

    // ==================== A-1 complete > incomplete ====================

    @Test
    void a1_completeBeatsIncompleteRegardlessOfStatus() {
        // complete 但 status 差（FAILED）> incomplete 但 status 好（INDEXED）
        KbDocument completeFailed = doc(10L, "FAILED", LocalDateTime.of(2026, 1, 1, 0, 0));
        KbDocument incompleteIndexed = doc(20L, "INDEXED", LocalDateTime.of(2026, 1, 1, 0, 0));

        CanonicalCandidate winner = selector.selectWinner(List.of(
                candidate(incompleteIndexed, 0, 0),
                candidate(completeFailed, 3, 3)));

        assertThat(winner.document().getId()).isEqualTo(10L);
    }

    // ==================== A-2 同 complete：statusRank 优先 ====================

    @Test
    void a2_sameCompleteStatusRankOrder() {
        KbDocument indexed = doc(1L, "INDEXED", LocalDateTime.of(2026, 1, 1, 0, 0));
        KbDocument parsed = doc(2L, "PARSED", LocalDateTime.of(2026, 1, 1, 0, 0));
        KbDocument uploaded = doc(3L, "UPLOADED", LocalDateTime.of(2026, 1, 1, 0, 0));
        KbDocument failed = doc(4L, "FAILED", LocalDateTime.of(2026, 1, 1, 0, 0));

        assertThat(selector.selectWinner(List.of(
                candidate(failed, 2, 2), candidate(uploaded, 2, 2),
                candidate(parsed, 2, 2), candidate(indexed, 2, 2))).document().getId())
                .isEqualTo(1L);
    }

    @Test
    void a2b_unknownStatusSortedLast() {
        KbDocument indexed = doc(1L, "INDEXED", LocalDateTime.of(2026, 1, 1, 0, 0));
        KbDocument unknown = doc(2L, "PARSING", LocalDateTime.of(2026, 1, 1, 0, 0)); // 未知 status

        assertThat(selector.selectWinner(List.of(
                candidate(unknown, 2, 2), candidate(indexed, 2, 2))).document().getId())
                .isEqualTo(1L);
    }

    // ==================== A-3 同 status：createdAt earlier ====================

    @Test
    void a3_sameStatusEarlierCreatedAtWins() {
        KbDocument later = doc(1L, "INDEXED", LocalDateTime.of(2026, 2, 1, 0, 0));
        KbDocument earlier = doc(2L, "INDEXED", LocalDateTime.of(2026, 1, 1, 0, 0));

        assertThat(selector.selectWinner(List.of(
                candidate(later, 2, 2), candidate(earlier, 2, 2))).document().getId())
                .isEqualTo(2L);
    }

    @Test
    void a3b_nullCreatedAtSortedLast() {
        KbDocument withDate = doc(1L, "INDEXED", LocalDateTime.of(2026, 1, 1, 0, 0));
        KbDocument withoutDate = doc(2L, "INDEXED", null);

        assertThat(selector.selectWinner(List.of(
                candidate(withoutDate, 2, 2), candidate(withDate, 2, 2))).document().getId())
                .isEqualTo(1L);
    }

    // ==================== A-4 同 createdAt：smaller id ====================

    @Test
    void a4_sameCreatedAtSmallerIdWins() {
        LocalDateTime sameTime = LocalDateTime.of(2026, 8, 10, 12, 0, 0);
        KbDocument id5 = doc(5L, "INDEXED", sameTime);
        KbDocument id3 = doc(3L, "INDEXED", sameTime);

        assertThat(selector.selectWinner(List.of(
                candidate(id5, 2, 2), candidate(id3, 2, 2))).document().getId())
                .isEqualTo(3L);
    }

    // ==================== A-5 真实结构模拟：FAILED+INDEXED+UPLOADED → INDEXED ====================

    @Test
    void a5_realStructureFailedIndexedUploadedWinsIndexed() {
        // Group A 真实结构：1 FAILED incomplete / 2 INDEXED vector-complete / 4 UPLOADED incomplete
        KbDocument doc1 = doc(1L, "FAILED", LocalDateTime.of(2026, 1, 1, 0, 0));
        KbDocument doc2 = doc(2L, "INDEXED", LocalDateTime.of(2026, 1, 2, 0, 0));
        KbDocument doc4 = doc(4L, "UPLOADED", LocalDateTime.of(2026, 1, 3, 0, 0));

        CanonicalCandidate winner = selector.selectWinner(List.of(
                candidate(doc1, 0, 0),
                candidate(doc2, 3, 3),
                candidate(doc4, 0, 0)));

        assertThat(winner.document().getId()).isEqualTo(2L);
    }

    // ==================== A-6 deterministic repeated calls ====================

    @Test
    void a6_repeatedCallsAreDeterministic() {
        KbDocument doc6 = doc(6L, "INDEXED", LocalDateTime.of(2026, 1, 1, 0, 0));
        KbDocument doc7 = doc(7L, "INDEXED", LocalDateTime.of(2026, 1, 2, 0, 0));
        KbDocument doc9 = doc(9L, "INDEXED", LocalDateTime.of(2026, 1, 3, 0, 0));
        KbDocument doc10 = doc(10L, "INDEXED", LocalDateTime.of(2026, 1, 4, 0, 0));
        List<CanonicalCandidate> candidates = List.of(
                candidate(doc7, 2, 2), candidate(doc6, 2, 2),
                candidate(doc10, 2, 2), candidate(doc9, 2, 2));

        Long first = selector.selectWinner(candidates).document().getId();
        Long second = selector.selectWinner(candidates).document().getId();
        Long third = selector.selectWinner(candidates).document().getId();

        assertThat(first).isEqualTo(6L);
        assertThat(second).isEqualTo(first);
        assertThat(third).isEqualTo(first);
    }

    // ==================== selector fail-closed ====================

    @Test
    void selector_rejectsEmptyOrNullCandidates() {
        assertThatThrownBy(() -> selector.selectWinner(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("候选列表不能为空");
        assertThatThrownBy(() -> selector.selectWinner(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("候选列表不能为空");
    }

    @Test
    void selector_failsClosedOnVectorInventoryAnomaly() {
        KbDocument doc = doc(1L, "INDEXED", LocalDateTime.of(2026, 1, 1, 0, 0));
        // vector=3 > chunk=2 → anomaly → HARD FAIL
        assertThatThrownBy(() -> selector.selectWinner(List.of(candidate(doc, 2, 3))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("vector inventory anomaly");
    }

    // ==================== B 组：vector completeness ====================

    @Test
    void b1_chunk2Vector2IsComplete() {
        assertThat(candidate(doc(1L, "INDEXED", null), 2, 2).completeness())
                .isEqualTo(VectorCompleteness.COMPLETE);
    }

    @Test
    void b2_chunk2Vector1IsIncomplete() {
        assertThat(candidate(doc(1L, "INDEXED", null), 2, 1).completeness())
                .isEqualTo(VectorCompleteness.INCOMPLETE);
    }

    @Test
    void b3_chunk0Vector0IsNotComplete() {
        assertThat(candidate(doc(1L, "FAILED", null), 0, 0).completeness())
                .isEqualTo(VectorCompleteness.INCOMPLETE);
    }

    @Test
    void b4_vectorGreaterThanChunkIsAnomaly() {
        assertThat(candidate(doc(1L, "INDEXED", null), 2, 3).completeness())
                .isEqualTo(VectorCompleteness.VECTOR_INVENTORY_ANOMALY);
    }

    @Test
    void b5_chunk1Vector1IsComplete() {
        assertThat(candidate(doc(1L, "INDEXED", null), 1, 1).completeness())
                .isEqualTo(VectorCompleteness.COMPLETE);
    }
}
