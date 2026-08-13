package com.shuhuayv.rag.migration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.shuhuayv.rag.entity.KbDocument;
import com.shuhuayv.rag.mapper.KbDocumentMapper;
import com.shuhuayv.rag.util.FileHashUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * 历史数据 {@code content_sha256} 回填器（PR-2A，默认关闭）。
 *
 * <p><b>唯一职责</b>：把 {@code kb_document} 中 {@code content_sha256 IS NULL} 的行，
 * 按其 {@code file_path} 指向文件的 <b>raw bytes</b> 计算 SHA-256 并写回。仅此一件事。</p>
 *
 * <p><b>明令禁止（职责边界，规格第22节）</b>，本类<b>绝不</b>：</p>
 * <ul>
 *   <li>自动软删任何文档；</li>
 *   <li>自动选择 canonical / 写 {@code canonical_document_id} / 写 {@code dedup_batch}；</li>
 *   <li>自动修改 {@code status}；</li>
 *   <li>自动删除任何文件；</li>
 *   <li>访问或修改 Qdrant；</li>
 *   <li>创建任何索引（含 M2 的 UNIQUE index）。</li>
 * </ul>
 *
 * <p><b>开关</b>：{@code app.migration.backfill-content-hash}，默认 {@code false}。
 * 只有显式设置为 {@code true} 才会运行。类级 {@link ConditionalOnProperty} 让 Bean 默认根本不注册，
 * {@link #run(ApplicationArguments)} 内还有一次 {@code enabled} 判断作为双保险。</p>
 *
 * <p><b>并发/重入安全</b>：写回使用条件更新
 * {@code UPDATE kb_document SET content_sha256 = ? WHERE id = ? AND content_sha256 IS NULL}，
 * 因此重复运行是幂等的：第二次运行不会覆盖任何已填值的行。</p>
 *
 * <p><b>哈希实现复用</b>：必须且只能使用 {@link FileHashUtil#sha256(Path)}，
 * 与上传路径共用同一套语义，禁止另写第二套哈希。</p>
 *
 * <p>🔴 <b>本轮未在任何真实数据库上运行</b>（{@code REAL_DATA_BACKFILL_ALLOWED = NO}），仅实现 + 单元测试。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.migration.backfill-content-hash", havingValue = "true")
public class ContentHashBackfillRunner implements ApplicationRunner {

    private final KbDocumentMapper kbDocumentMapper;

    /** 回填开关。默认 false，仅显式 true 才执行。 */
    private final boolean enabled;

    public ContentHashBackfillRunner(
            KbDocumentMapper kbDocumentMapper,
            @Value("${app.migration.backfill-content-hash:false}") boolean enabled) {
        this.kbDocumentMapper = kbDocumentMapper;
        this.enabled = enabled;
    }

    /**
     * 应用启动钩子。开关关闭时直接返回，<b>不发起任何数据库查询</b>。
     *
     * @param args 启动参数（未使用）
     */
    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            log.info("ContentHashBackfillRunner skipped: app.migration.backfill-content-hash=false");
            return;
        }

        BackfillStats stats = executeBackfill();
        log.info("ContentHashBackfill finished: total={}, filled={}, alreadyFilled={}, missingFile={}, ioError={}",
                stats.total(), stats.filled(), stats.alreadyFilled(), stats.missingFile(), stats.ioError());
    }

    /**
     * 执行回填并返回统计结果。
     *
     * <p>逐行处理，单行失败不会中断整批：文件缺失计入 {@code missingFile}，
     * 读取异常计入 {@code ioError}，两者都<b>不会</b>写入任何假 hash。</p>
     *
     * @return 本次回填的统计结果
     */
    public BackfillStats executeBackfill() {
        LambdaQueryWrapper<KbDocument> query = new LambdaQueryWrapper<KbDocument>()
                .isNull(KbDocument::getContentSha256)
                .orderByAsc(KbDocument::getId);

        List<KbDocument> pending = kbDocumentMapper.selectList(query);
        if (pending == null || pending.isEmpty()) {
            return new BackfillStats(0, 0, 0, 0, 0);
        }

        int total = pending.size();
        int filled = 0;
        int alreadyFilled = 0;
        int missingFile = 0;
        int ioError = 0;

        for (KbDocument document : pending) {
            if (document == null || document.getId() == null) {
                continue;
            }

            String rawPath = document.getFilePath();
            if (rawPath == null || rawPath.isBlank()) {
                missingFile++;
                log.warn("Backfill skipped, file_path is empty, documentId={}", document.getId());
                continue;
            }

            Path path = Paths.get(rawPath);
            if (!Files.exists(path) || Files.isDirectory(path)) {
                missingFile++;
                log.warn("Backfill skipped, file not found, documentId={}, path={}", document.getId(), rawPath);
                continue;
            }

            String hash;
            try {
                hash = FileHashUtil.sha256(path);
            } catch (RuntimeException e) {
                ioError++;
                log.error("Backfill failed to hash file, documentId={}, path={}", document.getId(), rawPath, e);
                continue;
            }

            // 条件更新：只在 content_sha256 仍为 NULL 时写入，保证并发安全与重复运行幂等。
            LambdaUpdateWrapper<KbDocument> update = new LambdaUpdateWrapper<KbDocument>()
                    .set(KbDocument::getContentSha256, hash)
                    .eq(KbDocument::getId, document.getId())
                    .isNull(KbDocument::getContentSha256);

            int affected = kbDocumentMapper.update(null, update);
            if (affected > 0) {
                filled++;
                log.info("Backfill filled content_sha256, documentId={}, contentSha256={}", document.getId(), hash);
            } else {
                // 条件未命中：该行的 content_sha256 已被其他执行者填充，本次不覆盖。
                alreadyFilled++;
                log.info("Backfill skipped, content_sha256 already filled by others, documentId={}", document.getId());
            }
        }

        return new BackfillStats(total, filled, alreadyFilled, missingFile, ioError);
    }

    /**
     * 回填统计。
     *
     * @param total         本次扫描到的 {@code content_sha256 IS NULL} 行数
     * @param filled        成功写入 hash 的行数
     * @param alreadyFilled 条件更新未命中（已被他人填充）的行数
     * @param missingFile   {@code file_path} 为空或文件不存在的行数（不写假 hash）
     * @param ioError       读取/哈希过程中发生异常的行数（不写假 hash）
     */
    public record BackfillStats(int total, int filled, int alreadyFilled, int missingFile, int ioError) {
    }
}
