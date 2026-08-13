package com.shuhuayv.rag.dedup;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;

/**
 * dedup_batch 批次 ID 生成器（D3）。
 *
 * <p><b>格式</b>：{@code dedup-YYYYMMDD-NN}（例如 {@code dedup-20260812-01}），长度 &lt;= 32，
 * 字符集受控（小写字母、数字、连字符）。</p>
 *
 * <p><b>禁止硬编码</b>：代码<b>不得</b>写死 {@code dedup-20260812-01} 之类的具体批次号；
 * 真实批次由执行 Gate 固定，本生成器只负责「按日期+序号运行时生成」或「校验外部传入批次」。
 * 每日本批次上限为 99（NN ∈ [1,99]），超出即拒绝生成（fail-closed）。</p>
 */
@Component
public class DedupBatchIdGenerator {

    /** 日期格式化：yyyyMMdd（BASIC_ISO_DATE）。 */
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.BASIC_ISO_DATE;

    /** 合法批次字面量：dedup-8位日期-2位序号。 */
    private static final Pattern BATCH_PATTERN = Pattern.compile("^dedup-\\d{8}-\\d{2}$");

    /** 批次 ID 最大长度（对齐 DDL dedup_batch VARCHAR(32)）。 */
    private static final int MAX_LENGTH = 32;

    /** 每日序号上限。 */
    private static final int MAX_SEQUENCE = 99;

    /**
     * 运行时生成批次 ID。
     *
     * @param date     批次日期（通常为执行当天）
     * @param sequence 当日序号，范围 [1, 99]
     * @return 形如 {@code dedup-20260812-01} 的批次 ID
     * @throws IllegalArgumentException 参数非法（date 为空或 sequence 越界）
     */
    public String generate(LocalDate date, int sequence) {
        if (date == null) {
            throw new IllegalArgumentException("date 不能为空");
        }
        if (sequence < 1 || sequence > MAX_SEQUENCE) {
            throw new IllegalArgumentException("sequence 必须在 [1, " + MAX_SEQUENCE + "]，实际 " + sequence);
        }
        String batchId = String.format("dedup-%s-%02d", date.format(DATE_FORMATTER), sequence);
        if (batchId.length() > MAX_LENGTH) {
            throw new IllegalStateException("生成 batchId 超长（> " + MAX_LENGTH + "）：" + batchId.length());
        }
        return batchId;
    }

    /**
     * 校验外部传入的批次 ID（格式 + 长度，fail-closed）。
     *
     * @param batchId 待校验批次
     * @return {@code true} 表示合法
     */
    public boolean isValid(String batchId) {
        if (batchId == null) {
            return false;
        }
        if (batchId.length() > MAX_LENGTH) {
            return false;
        }
        return BATCH_PATTERN.matcher(batchId).matches();
    }
}
