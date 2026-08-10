package com.shuhuayv.rag.migration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.shuhuayv.rag.entity.KbDocument;
import com.shuhuayv.rag.mapper.KbDocumentMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ContentHashBackfillRunner 纯单元测试（BACKFILL-01 ~ BACKFILL-05）。
 *
 * <p>mock {@link KbDocumentMapper}，不连真实 DB、不启动 Spring。LambdaQueryWrapper / LambdaUpdateWrapper
 * 需要 TableInfo 缓存，在 {@code @BeforeEach} 中显式初始化。</p>
 */
class ContentHashBackfillRunnerTest {

    @TempDir
    Path tempDir;

    private KbDocumentMapper mapper;
    private ContentHashBackfillRunner runner;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), KbDocument.class);
        mapper = mock(KbDocumentMapper.class);
        // enabled=false：仅测试 executeBackfill() 逻辑，run() 的开关分支由配置与 @ConditionalOnProperty 保证。
        runner = new ContentHashBackfillRunner(mapper, false);
    }

    @Test
    void backfill01_emptyPendingReturnsAllZero() {
        when(mapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.emptyList());
        ContentHashBackfillRunner.BackfillStats stats = runner.executeBackfill();
        assertThat(stats).isEqualTo(new ContentHashBackfillRunner.BackfillStats(0, 0, 0, 0, 0));
    }

    @Test
    void backfill02_validFileFilledOnce() throws Exception {
        Path file = tempDir.resolve("doc.txt");
        Files.writeString(file, "hello backfill");
        KbDocument doc = new KbDocument();
        doc.setId(1L);
        doc.setFilePath(file.toString());

        when(mapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(doc));
        when(mapper.update(any(), any(LambdaUpdateWrapper.class))).thenReturn(1);

        ContentHashBackfillRunner.BackfillStats stats = runner.executeBackfill();
        assertThat(stats.total()).isEqualTo(1);
        assertThat(stats.filled()).isEqualTo(1);
        assertThat(stats.missingFile()).isEqualTo(0);
        assertThat(stats.ioError()).isEqualTo(0);
        verify(mapper, times(1)).update(any(), any(LambdaUpdateWrapper.class));
    }

    @Test
    void backfill03_blankFilePathCountsMissing() {
        KbDocument doc = new KbDocument();
        doc.setId(2L);
        doc.setFilePath("");
        when(mapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(doc));

        ContentHashBackfillRunner.BackfillStats stats = runner.executeBackfill();
        assertThat(stats.total()).isEqualTo(1);
        assertThat(stats.missingFile()).isEqualTo(1);
        assertThat(stats.filled()).isEqualTo(0);
    }

    @Test
    void backfill04_missingFileCountsMissing() {
        KbDocument doc = new KbDocument();
        doc.setId(3L);
        doc.setFilePath(tempDir.resolve("ghost.txt").toString());
        when(mapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(doc));

        ContentHashBackfillRunner.BackfillStats stats = runner.executeBackfill();
        assertThat(stats.total()).isEqualTo(1);
        assertThat(stats.missingFile()).isEqualTo(1);
        assertThat(stats.filled()).isEqualTo(0);
    }

    @Test
    void backfill05_alreadyFilledByOthersCountsAlreadyFilled() throws Exception {
        Path file = tempDir.resolve("doc2.txt");
        Files.writeString(file, "already there");
        KbDocument doc = new KbDocument();
        doc.setId(4L);
        doc.setFilePath(file.toString());

        when(mapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(doc));
        // 条件更新未命中（content_sha256 已被他人填充）→ 返回 0。
        when(mapper.update(any(), any(LambdaUpdateWrapper.class))).thenReturn(0);

        ContentHashBackfillRunner.BackfillStats stats = runner.executeBackfill();
        assertThat(stats.total()).isEqualTo(1);
        assertThat(stats.alreadyFilled()).isEqualTo(1);
        assertThat(stats.filled()).isEqualTo(0);
    }
}
