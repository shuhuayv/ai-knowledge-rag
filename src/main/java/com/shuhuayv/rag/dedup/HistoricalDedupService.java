package com.shuhuayv.rag.dedup;

/**
 * PR-3 Historical Dedup 服务（职责：前置条件校验 → 计划计算 → Phase A MySQL 单事务 → Phase B Qdrant 补偿清理）。
 *
 * <p>安全设计：</p>
 * <ul>
 *   <li>默认绝不自动执行：Runner 默认关闭（{@code app.migration.historical-dedup=false}），
 *       本服务仅在被显式调用时运行；</li>
 *   <li>dry-run 默认 true：{@code executeDedup(dryRun=true)} 只计算计划，<b>不写 DB / Qdrant</b>；</li>
 *   <li>fail-closed preconditions：任一前置条件不满足即抛异常，绝不静默继续；</li>
 *   <li>Phase A（MySQL）与 Phase B（Qdrant）分离：Qdrant 不在 DB 事务内，
 *       Qdrant 失败不反向恢复 DB，仅标记 PR3_QDRANT_CLEANUP_PENDING。</li>
 * </ul>
 */
public interface HistoricalDedupService {

    /**
     * 执行历史去重治理。
     *
     * @param command 执行命令（dryRun 标记 + 可选外部固定 batchId）
     * @return 执行结果（dry-run 或真实执行）
     * @throws IllegalStateException 任一前置条件不满足（fail-closed）
     */
    HistoricalDedupResult executeDedup(HistoricalDedupCommand command);
}
