package com.shuhuayv.rag.dedup;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * PR-3 Historical Dedup 启动 Runner（默认关闭 + dry-run 默认 true 双保险）。
 *
 * <p><b>安全开关（I 组测试契约）</b>：</p>
 * <ul>
 *   <li>类级 {@link ConditionalOnProperty}：{@code app.migration.historical-dedup} 非 {@code true}
 *       （缺失 / false）时 Bean 根本不注册 → 不执行；</li>
 *   <li>{@code run()} 内再次判断 {@code enabled}（双保险）：即使 Bean 被注册，
 *       {@code enabled=false} 也直接返回、<b>不发起任何查询</b>；</li>
 *   <li>dry-run 默认 true（{@code app.migration.historical-dedup-dry-run=true}）：
 *       只有 {@code historical-dedup=true AND dry-run=false} 才可能真实写 DB/Qdrant。</li>
 * </ul>
 *
 * <p><b>输出状态位</b>：执行时输出 {@code PR3_ENABLE} / {@code PR3_DRY_RUN} 便于审计。</p>
 *
 * <p><b>本轮 CODE-ONLY</b>：默认配置下本 Runner 不注册、不执行，真实历史数据不变。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.migration.historical-dedup", havingValue = "true")
public class HistoricalDedupRunner implements ApplicationRunner {

    private final HistoricalDedupService historicalDedupService;
    private final boolean enabled;
    private final boolean dryRun;

    public HistoricalDedupRunner(HistoricalDedupService historicalDedupService,
                                 @Value("${app.migration.historical-dedup:false}") boolean enabled,
                                 @Value("${app.migration.historical-dedup-dry-run:true}") boolean dryRun) {
        this.historicalDedupService = historicalDedupService;
        this.enabled = enabled;
        this.dryRun = dryRun;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            log.info("HistoricalDedupRunner skipped: app.migration.historical-dedup=false");
            return;
        }

        log.info("PR3_ENABLE=true, PR3_DRY_RUN={}", dryRun);
        HistoricalDedupResult result = historicalDedupService.executeDedup(
                new HistoricalDedupCommand(dryRun, null));

        log.info("HistoricalDedup finished: dryRun={}, batchId={}, groups={}, winners={}, duplicates={}, "
                        + "qdrantCleanupPending={}, managedCleaned={}, legacySkipped={}",
                result.dryRun(), result.batchId(), result.groups(), result.winners(), result.duplicates(),
                result.qdrantCleanupPending(), result.qdrantManagedCollectionsCleaned(),
                result.qdrantLegacyCollectionsSkipped());
    }
}
