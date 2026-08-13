package com.shuhuayv.rag.dedup;

/**
 * PR-3 执行命令（D3 / I 组测试契约）。
 *
 * @param dryRun  dry-run 标记：{@code true} 只计算计划、<b>绝不</b>写 DB / Qdrant（双保险）；
 *                {@code false} 才允许真实写入
 * @param batchId 外部固定的批次号；为 {@code null} 时由 {@link DedupBatchIdGenerator} 运行时生成
 */
public record HistoricalDedupCommand(boolean dryRun, String batchId) {

    /** 默认 dry-run 命令（batchId 运行时生成）。 */
    public static HistoricalDedupCommand dryRunCommand() {
        return new HistoricalDedupCommand(true, null);
    }
}
