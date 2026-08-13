package com.shuhuayv.rag.dedup;

import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * I 组测试：HistoricalDedupRunner 默认安全（双保险）。
 *
 * <p>纯 Mock，不连真实 DB/Qdrant（REAL_PR3_EXECUTION_TRIGGERED=NO）。</p>
 */
class HistoricalDedupRunnerTest {

    private final HistoricalDedupService service = mock(HistoricalDedupService.class);
    private final ApplicationArguments args = mock(ApplicationArguments.class);

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        org.mockito.Mockito.when(service.executeDedup(any(HistoricalDedupCommand.class)))
                .thenReturn(HistoricalDedupResult.empty(true));
    }

    @Test
    void i1_propertyAbsentBeanNotRegisteredByConditionalOnProperty() {
        // 类级 @ConditionalOnProperty(havingValue=true)：非 true 时 Bean 根本不注册 → 不执行。
        ConditionalOnProperty annotation =
                HistoricalDedupRunner.class.getAnnotation(ConditionalOnProperty.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.name()).contains("app.migration.historical-dedup");
        assertThat(annotation.havingValue()).isEqualTo("true");
    }

    @Test
    void i2_enabledFalseDoesNotTouchService() {
        HistoricalDedupRunner runner = new HistoricalDedupRunner(service, false, true);
        runner.run(args);
        verifyNoInteractions(service);
    }

    @Test
    void i3_enabledTrueDryRunTrueCallsServiceInDryRunMode() {
        HistoricalDedupRunner runner = new HistoricalDedupRunner(service, true, true);
        runner.run(args);
        verify(service).executeDedup(any(HistoricalDedupCommand.class));
    }

    @Test
    void i4_enabledTrueDryRunFalseCallsServiceInRealMode() {
        HistoricalDedupRunner runner = new HistoricalDedupRunner(service, true, false);
        runner.run(args);
        verify(service).executeDedup(any(HistoricalDedupCommand.class));
    }

    @Test
    void i5_commandCarriesDryRunFlagThrough() {
        HistoricalDedupRunner runner = new HistoricalDedupRunner(service, true, true);
        runner.run(args);
        var captor = org.mockito.ArgumentCaptor.forClass(HistoricalDedupCommand.class);
        verify(service).executeDedup(captor.capture());
        assertThat(captor.getValue().dryRun()).isTrue();
        assertThat(captor.getValue().batchId()).isNull();
    }
}
