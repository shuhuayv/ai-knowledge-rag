package com.shuhuayv.rag.dedup;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * canonical governance selector（D1 / D6-B，冷路径治理选择器）。
 *
 * <p><b>规则（固定不可调整）</b>：
 * {@code VECTOR_COMPLETENESS → STATUS_RANK → CREATED_AT → ID}，即：</p>
 * <ol>
 *   <li><b>VECTOR_COMPLETENESS</b>：{@link VectorCompleteness#COMPLETE} 优先于
 *       {@link VectorCompleteness#INCOMPLETE}；</li>
 *   <li><b>STATUS_RANK</b>：INDEXED=0 / PARSED=1 / UPLOADED=2 / FAILED=3 / unknown=4，数值小者优先；</li>
 *   <li><b>CREATED_AT</b>：更早者优先（null 排最后）；</li>
 *   <li><b>ID</b>：更小者优先（最终确定性 tie-breaker）。</li>
 * </ol>
 *
 * <p><b>通用确定性</b>：本类为<b>纯逻辑</b>，不含任何 document id 硬编码；相同输入恒得相同输出。
 * 当前真实 3 组的预期结果（fixture 验证）：A(1,2,4)→2、B(6,7,9,10)→6、C(5,8)→5。</p>
 *
 * <p><b>职责边界（D6）</b>：本类是 <b>historical governance canonical selector</b>（冷路径），
 * 与上传热路径的 {@code findPreferredActiveDuplicate}（statusRank→createdAt→id，禁访 Qdrant）
 * <b>必须分开</b>，不能混成一个 comparator。前者允许访问向量完整度（kb_chunk/kb_vector_record），
 * 后者不得。</p>
 *
 * <p><b>Fail-closed</b>：任一候选出现 {@link VectorCompleteness#VECTOR_INVENTORY_ANOMALY} 时
 * 立即抛 {@link IllegalStateException}，PR-3 不得继续。</p>
 */
@Component
public class CanonicalDocumentSelector {

    /** status 业务价值排序表：数值越小越优先。 */
    private static final Map<String, Integer> STATUS_RANK = Map.of(
            "INDEXED", 0,
            "PARSED", 1,
            "UPLOADED", 2,
            "FAILED", 3);

    /** 未知 status 或 null 的兜底 rank，严格排在所有已知 status 之后。 */
    private static final int UNKNOWN_STATUS_RANK = 4;

    /**
     * 确定性比较器，顺序固定不可调整：
     * {@code completeness ASC → statusRank ASC → createdAt ASC(nulls last) → id ASC(nulls last)}。
     */
    private static final Comparator<CanonicalCandidate> SELECTOR =
            Comparator.comparing(CanonicalCandidate::completeness)
                    .thenComparingInt(candidate -> statusRank(candidate.document().getStatus()))
                    .thenComparing(candidate -> candidate.document().getCreatedAt(),
                            Comparator.nullsLast(Comparator.<LocalDateTime>naturalOrder()))
                    .thenComparing(candidate -> candidate.document().getId(),
                            Comparator.nullsLast(Comparator.<Long>naturalOrder()));

    /**
     * 从候选列表中选出唯一的 canonical winner。
     *
     * @param candidates 同一内容 hash 组内的全部候选（不可为空）
     * @return winner 候选
     * @throws IllegalArgumentException 候选列表为空或含非法项
     * @throws IllegalStateException    任一候选存在 vector inventory anomaly（fail-closed）
     */
    public CanonicalCandidate selectWinner(List<CanonicalCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            throw new IllegalArgumentException("canonical selector 候选列表不能为空");
        }
        for (CanonicalCandidate candidate : candidates) {
            if (candidate == null || candidate.document() == null || candidate.document().getId() == null) {
                throw new IllegalArgumentException("canonical selector 候选包含非法项（null 或缺少 document id）");
            }
            if (candidate.completeness() == VectorCompleteness.VECTOR_INVENTORY_ANOMALY) {
                throw new IllegalStateException(
                        "vector inventory anomaly，PR-3 fail closed，documentId=" + candidate.document().getId());
            }
        }
        return candidates.stream().min(SELECTOR).orElse(null);
    }

    /**
     * status → rank 映射。数值越小优先级越高；未知或 null 统一归入兜底档。
     *
     * @param status 文档状态字面量（INDEXED / PARSED / UPLOADED / FAILED 或其他）
     * @return 该状态对应的 rank
     */
    private static int statusRank(String status) {
        if (status == null) {
            return UNKNOWN_STATUS_RANK;
        }
        return STATUS_RANK.getOrDefault(status, UNKNOWN_STATUS_RANK);
    }
}
