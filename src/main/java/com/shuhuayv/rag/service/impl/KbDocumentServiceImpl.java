package com.shuhuayv.rag.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.shuhuayv.rag.dedup.QdrantCleanupService;
import com.shuhuayv.rag.dedup.SoftDeleteSemantics;
import com.shuhuayv.rag.entity.KbDocument;
import com.shuhuayv.rag.mapper.KbDocumentMapper;
import com.shuhuayv.rag.service.DocumentUploadResult;
import com.shuhuayv.rag.service.KbDocumentService;
import com.shuhuayv.rag.util.FileHashUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
public class KbDocumentServiceImpl extends ServiceImpl<KbDocumentMapper, KbDocument> implements KbDocumentService {

    @Value("${app.upload.dir:uploads}")
    private String uploadDir;

    /**
     * Qdrant 安全补偿式 cleanup（软删后删除该文档 exact points；D7）。
     * 普通上传/读取路径不依赖本字段；仅 {@link #deleteDocument} 使用。
     */
    @Autowired
    private QdrantCleanupService qdrantCleanupService;

    /**
     * 软删时是否删除原始物理文件（D7，默认 false）。
     * 默认保留原始文件保 rollback；本轮绝不删除真实文件（PHYSICAL_FILE_DELETE_ON_SOFT_DELETE=NO）。
     */
    @Value("${app.dedup.physical-file-delete-on-soft-delete:false}")
    private boolean physicalFileDeleteOnSoftDelete;

    private static final long MAX_FILE_SIZE = 50 * 1024 * 1024;

    /** {@code is_deleted} 的 active 取值。非 0 表示已软删（未来为该行自身 document id）。 */
    private static final long ACTIVE_FLAG = 0L;

    /**
     * status 业务价值排序表：<b>数值越小越优先</b>。
     *
     * <p>INDEXED（已向量化，价值最高）→ PARSED → UPLOADED → FAILED。</p>
     */
    private static final Map<String, Integer> STATUS_RANK = Map.of(
            "INDEXED", 0,
            "PARSED", 1,
            "UPLOADED", 2,
            "FAILED", 3);

    /**
     * 未知 status 或 {@code null} 的兜底 rank，严格排在所有已知 status 之后。
     *
     * <p>注：DDL 注释中出现过 {@code PARSING} 状态但代码从未写入，同样归入本兜底档，
     * 排序确定性由后续的 createdAt / id 比较保证。</p>
     */
    private static final int UNKNOWN_STATUS_RANK = 4;

    /**
     * 「首选 active 重复文档」的确定性比较器，顺序固定不可调整：
     * {@code statusRank ASC → createdAt ASC(nulls last) → id ASC(nulls last)}。
     */
    private static final Comparator<KbDocument> PREFERRED_ACTIVE_DUPLICATE =
            Comparator.comparingInt((KbDocument d) -> statusRank(d.getStatus()))
                    .thenComparing(KbDocument::getCreatedAt,
                            Comparator.nullsLast(Comparator.<LocalDateTime>naturalOrder()))
                    .thenComparing(KbDocument::getId,
                            Comparator.nullsLast(Comparator.<Long>naturalOrder()));

    /**
     * {@inheritDoc}
     *
     * <p><b>并发语义（诚实声明）</b>：</p>
     * <ul>
     *   <li><b>PRE_M2</b>（当前真实 DB 状态，UNIQUE 约束尚未创建）：仅应用层 query-first → insert，
     *       属 <i>best-effort application dedup</i>，<b>无法</b>提供跨线程/跨进程的严格并发唯一性。</li>
     *   <li><b>POST_M2</b>（未来执行 M2 之后）：DB 层 {@code UNIQUE(content_sha256, is_deleted)} 兜底，
     *       配合本方法的 {@link DuplicateKeyException} 重查 fallback，达成 DB-enforced 唯一。</li>
     * </ul>
     */
    @Override
    public DocumentUploadResult uploadDocument(MultipartFile file) {
        // ---------- 阶段 1 · 校验（旧行为完全保留，顺序与异常消息均不变）----------
        if (file.isEmpty()) {
            throw new IllegalArgumentException("文件不能为空");
        }

        if (file.getSize() > MAX_FILE_SIZE) {
            throw new IllegalArgumentException("文件大小不能超过 50MB");
        }

        String originalFilename = file.getOriginalFilename();
        String fileType = getFileType(originalFilename);

        if (!"TXT".equals(fileType) && !"PDF".equals(fileType)) {
            throw new IllegalArgumentException("仅支持 TXT 和 PDF 文件格式");
        }

        // ---------- 阶段 2 · 落盘（先落盘后计算 hash；禁止先建 DB 行）----------
        String storedFilename = UUID.randomUUID().toString() + "_" + originalFilename;
        Path uploadPath = Paths.get(uploadDir).toAbsolutePath().normalize();
        Path filePath = null;
        try {
            Files.createDirectories(uploadPath);
            filePath = uploadPath.resolve(storedFilename);
            file.transferTo(filePath.toFile());
        } catch (IOException e) {
            // transferTo 可能已写出部分文件：filePath 已确定则 best-effort 删除，
            // 原 IOException 仍是主异常 cause；清理失败以 suppressed 挂在其上（不覆盖根因）。
            log.error("Failed to upload file", e);
            if (filePath != null) {
                try {
                    Files.deleteIfExists(filePath);
                } catch (IOException cleanupFailure) {
                    e.addSuppressed(cleanupFailure);
                }
            }
            throw new RuntimeException("文件上传失败", e);
        }

        // ---------- 阶段 3 · raw-byte SHA-256（唯一 hash 入口）----------
        String contentSha256;
        try {
            contentSha256 = FileHashUtil.sha256(filePath);
        } catch (RuntimeException hashFailure) {
            // hash 失败绝不允许插入 DB 行（新数据必须保证 content_sha256 != NULL）。
            log.error("内容哈希计算失败，不会写入 kb_document，path={}", filePath, hashFailure);
            RuntimeException uploadFailure =
                    new RuntimeException("文件上传失败: 内容哈希计算失败", hashFailure);
            try {
                cleanupUploadedFile(filePath);
            } catch (RuntimeException cleanupFailure) {
                // 不静默吞掉：cleanupUploadedFile 内已 log.error，此处作为 suppressed 一并抛出，
                // 保证「哈希失败」根因与「清理失败」同时可诊断。
                uploadFailure.addSuppressed(cleanupFailure);
            }
            throw uploadFailure;
        }

        // ---------- 阶段 4 · 内容去重（只查 MySQL，绝不访问 Qdrant）----------
        // 路径 A：去重查询自身失败（如 MySQL 不可用）→ 清理本次新写文件 → rethrow 原 DB 异常。
        // 此时 hash 已算完、文件已落盘、DB 行未建，必须保证不留下孤儿文件。
        KbDocument existing;
        try {
            existing = findPreferredActiveDuplicate(contentSha256);
        } catch (RuntimeException lookupFailure) {
            log.error("内容去重查询失败，清理本次新落盘文件后上抛，path={}, contentSha256={}",
                    filePath, contentSha256, lookupFailure);
            throw cleanupOnFailure(filePath, lookupFailure);
        }
        if (existing != null) {
            cleanupUploadedFile(filePath);
            log.info("Duplicate upload detected, returning existing document, id={}, contentSha256={}",
                    existing.getId(), contentSha256);
            return new DocumentUploadResult(existing, true);
        }

        KbDocument document = new KbDocument();
        document.setFileName(originalFilename);
        document.setFileType(fileType);
        document.setFilePath(filePath.toString());
        document.setFileSize(file.getSize());
        document.setStatus("UPLOADED");
        document.setContentSha256(contentSha256);
        document.setIsDeleted(ACTIVE_FLAG);

        try {
            save(document);
        } catch (DuplicateKeyException race) {
            // 仅在 M2 已执行（UNIQUE(content_sha256, is_deleted) 存在）时才可能触发：
            // 另一并发请求在本次 query 与 insert 之间抢先插入了同 hash 的 active 行。
            KbDocument winner = findPreferredActiveDuplicate(contentSha256);
            if (winner == null) {
                // 重查仍为空 → 不能断定是内容重复，可能是其他 DB 约束冲突。
                // 路径 C：清理本次新写文件后原样上抛原 DuplicateKeyException，
                // 绝不把所有 DuplicateKeyException 都当 content duplicate 吞掉。
                log.error("DuplicateKeyException 无法归因为内容重复（重查 active 行为空），"
                                + "清理本次新落盘文件后原样上抛，path={}, contentSha256={}",
                        filePath, contentSha256, race);
                throw cleanupOnFailure(filePath, race);
            }
            cleanupUploadedFile(filePath);
            log.warn("并发重复上传命中 DB unique 约束，返回已有文档，id={}, contentSha256={}",
                    winner.getId(), contentSha256);
            return new DocumentUploadResult(winner, true);
        } catch (RuntimeException saveFailure) {
            // 路径 B：普通 save 失败（非 DuplicateKeyException 的 RuntimeException /
            // DataAccessException，如 mapper.insert 抛 DataAccessResourceFailureException）。
            // 本次新写文件尚无人认领 → 必须清理后 rethrow 原异常，禁止留下孤儿文件。
            log.error("保存文档失败，清理本次新落盘文件后上抛，path={}, contentSha256={}",
                    filePath, contentSha256, saveFailure);
            throw cleanupOnFailure(filePath, saveFailure);
        }

        log.info("Document uploaded, id={}, fileName={}, contentSha256={}",
                document.getId(), originalFilename, contentSha256);
        return new DocumentUploadResult(document, false);
    }

    /**
     * {@inheritDoc}
     *
     * <p><b>为什么不能用 {@code .one()} / {@code getOne()} / {@code selectOne()}</b>：
     * 真实历史库在 backfill 之后、PR-3 去重治理之前，仍会存在多个 active 重复组
     * （例如 company_policy：doc1 FAILED、doc2 INDEXED、doc4 UPLOADED 共享同一 hash）。
     * 单行查询会在该过渡阶段直接抛 TooManyResults，属错误实现。
     * 因此本方法使用 {@code selectList} 并在 <b>Java 侧</b>做确定性优选。</p>
     *
     * <p><b>职责边界（重要）</b>：本方法是 <b>upload transitional selector</b>，
     * 只回答「重复上传时该把哪个已有文档返回给调用方」，
     * <b>不是</b>历史数据治理使用的 canonical selector。
     * 正式 governance 的 canonical 规则还包含 vector completeness（向量完整度）等维度；
     * 而上传属于热路径，<b>不得</b>为了计算 vector_rank 去实时访问 Qdrant。
     * 待 PR-3 治理完成后，同 hash 的 active 行只会剩 1 个，本规则自然退化为单行返回。</p>
     *
     * <p>优选顺序（确定性，固定不可调整）：
     * {@code statusRank ASC → createdAt ASC(nulls last) → id ASC(nulls last)}。</p>
     */
    @Override
    public KbDocument findPreferredActiveDuplicate(String contentSha256) {
        if (contentSha256 == null || contentSha256.isBlank()) {
            return null;
        }

        LambdaQueryWrapper<KbDocument> wrapper = new LambdaQueryWrapper<KbDocument>()
                .eq(KbDocument::getContentSha256, contentSha256)
                .eq(KbDocument::getIsDeleted, ACTIVE_FLAG)
                .orderByAsc(KbDocument::getId);

        List<KbDocument> candidates = getBaseMapper().selectList(wrapper);
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }

        return candidates.stream()
                .filter(Objects::nonNull)
                .min(PREFERRED_ACTIVE_DUPLICATE)
                .orElse(null);
    }

    /**
     * status → 优选 rank 映射。数值越小优先级越高；未知或 {@code null} 统一归入兜底档。
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

    /**
     * 删除本次新落盘、但因内容重复而不再需要的临时文件。
     *
     * <p>契约：连续上传同一内容 N 次，{@code uploads/} 目录只应保留 canonical/原有的那 1 个文件，
     * 不允许每次都堆积一个新的 {@code UUID_原名} 副本。</p>
     *
     * <p><b>失败处理红线</b>：删除失败<b>禁止</b>静默 catch。必须 {@code log.error} 并抛出可诊断异常，
     * 绝不允许「返回 duplicate=true 的同时偷偷留下无限增长的孤儿文件」。</p>
     *
     * @param path 待清理的文件路径
     * @throws IllegalStateException 删除失败（可能残留孤儿文件，需人工介入）
     */
    private void cleanupUploadedFile(Path path) {
        if (path == null) {
            return;
        }
        try {
            if (Files.deleteIfExists(path)) {
                log.info("重复上传的新落盘文件已清理，path={}", path);
            }
        } catch (IOException e) {
            log.error("重复上传临时文件清理失败，可能残留孤儿文件，path={}", path, e);
            throw new IllegalStateException("重复上传清理失败，可能残留孤儿文件: " + path, e);
        }
    }

    /**
     * 上传失败路径的统一清理助手（BLOCKER-1 不变量）。
     *
     * <p>语义：清理本次新落盘文件（best-effort），但<b>绝不</b>让清理失败覆盖真正的 DB 根因。
     * 若清理失败，将其作为 {@code suppressed} 挂在原始异常上，然后原样返回原始异常供调用方抛出。</p>
     *
     * <p>适用路径：</p>
     * <ul>
     *   <li>A：{@code findPreferredActiveDuplicate} 查询抛 RuntimeException / DataAccessException；</li>
     *   <li>B：{@code save} 抛非 DuplicateKeyException 的 RuntimeException / DataAccessException；</li>
     *   <li>C：{@code save} 抛 DuplicateKeyException 且重查 winner == null。</li>
     * </ul>
     *
     * @param path     本次新落盘的文件路径（可为 null，此时视为无需清理）
     * @param original 真正的失败根因，绝不能被覆盖
     * @return 原异常（调用方应 {@code throw} 它）
     */
    private RuntimeException cleanupOnFailure(Path path, RuntimeException original) {
        try {
            cleanupUploadedFile(path);
        } catch (RuntimeException cleanupFailure) {
            // 禁止 cleanup 覆盖 DB 根因：清理失败仅作为 suppressed 附加，主异常保持不变。
            original.addSuppressed(cleanupFailure);
        }
        return original;
    }

    @Override
    public List<KbDocument> listDocuments() {
        // D5：active-only。soft-deleted 行（is_deleted != 0）不返回。
        // 直接用 baseMapper 而非 lambdaQuery()（后者在纯 mock 单测中需要真实 mapper proxy）。
        return getBaseMapper().selectList(new LambdaQueryWrapper<KbDocument>()
                .eq(KbDocument::getIsDeleted, ACTIVE_FLAG)
                .orderByDesc(KbDocument::getId));
    }

    @Override
    public IPage<KbDocument> pageDocuments(long pageNum, long pageSize) {
        // D5：active-only。soft-deleted 行（is_deleted != 0）不返回。
        return getBaseMapper().selectPage(new Page<>(pageNum, pageSize),
                new LambdaQueryWrapper<KbDocument>()
                        .eq(KbDocument::getIsDeleted, ACTIVE_FLAG)
                        .orderByDesc(KbDocument::getId));
    }

    @Override
    public KbDocument getDocumentById(Long id) {
        KbDocument document = getById(id);
        if (document == null || !SoftDeleteSemantics.isActive(document.getIsDeleted())) {
            // D5：soft-deleted 行视为 not found（沿现有异常契约）。
            throw new IllegalArgumentException("文档不存在");
        }
        return document;
    }

    /**
     * {@inheritDoc}
     *
     * <p>一次 SQL {@code WHERE id IN (...) AND is_deleted = 0} 批量返回 active id；
     * Search 层只对 unique document IDs 调用一次，禁止 N+1（H 组测试契约）。</p>
     */
    @Override
    public Set<Long> findActiveDocumentIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Set.of();
        }
        List<Long> uniqueIds = ids.stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (uniqueIds.isEmpty()) {
            return Set.of();
        }
        List<KbDocument> active = getBaseMapper().selectList(new LambdaQueryWrapper<KbDocument>()
                .in(KbDocument::getId, uniqueIds)
                .eq(KbDocument::getIsDeleted, ACTIVE_FLAG));
        Set<Long> result = new HashSet<>();
        if (active != null) {
            for (KbDocument document : active) {
                if (document != null && document.getId() != null) {
                    result.add(document.getId());
                }
            }
        }
        return result;
    }

    @Override
    public void deleteDocument(Long id) {
        KbDocument document = getById(id);
        if (document == null || !SoftDeleteSemantics.isActive(document.getIsDeleted())) {
            // 不存在或已软删：重复删除按 not found 契约处理（E 组测试契约）。
            throw new IllegalArgumentException("文档不存在");
        }

        // D7：物理删除 → soft delete。保留 row 作 tombstone；不得物理删除 DB row。
        // 乐观守卫：只对仍为 active 的行生效（并发下二次删除 / 已治理行不会被动）。
        LambdaUpdateWrapper<KbDocument> update = new LambdaUpdateWrapper<KbDocument>()
                .set(KbDocument::getIsDeleted, SoftDeleteSemantics.deletedMarker(id))
                .eq(KbDocument::getId, id)
                .eq(KbDocument::getIsDeleted, ACTIVE_FLAG);
        int affected = getBaseMapper().update(null, update);
        if (affected != 1) {
            throw new IllegalStateException("软删失败（乐观守卫未命中），id=" + id);
        }

        // D7：Qdrant points 安全补偿式 cleanup（失败不反向恢复 DB，可单独重跑）。
        try {
            if (qdrantCleanupService != null) {
                qdrantCleanupService.cleanupForDocument(id);
            }
        } catch (Exception e) {
            log.warn("软删后 Qdrant 补偿清理失败（DB 保持 soft-deleted 状态，不自动反向恢复），documentId={}", id, e);
        }

        // D7：物理文件默认不删（PHYSICAL_FILE_DELETE_ON_SOFT_DELETE=NO），保留原始文件保 rollback。
        // 若产品明确要求删除物理文件，须先报告主控 AI，且只能通过显式配置开启。
        if (physicalFileDeleteOnSoftDelete) {
            deletePhysicalFileIfExists(document.getFilePath());
        }

        log.info("Document soft deleted, id={}, isDeleted={}", id, id);
    }

    /**
     * 删除文档的原始物理文件（仅当显式开启 {@code physicalFileDeleteOnSoftDelete} 时调用；
     * 默认关闭，本轮不删任何真实文件）。失败仅 warn，不阻断软删流程。
     */
    private void deletePhysicalFileIfExists(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return;
        }
        try {
            File file = new File(filePath);
            if (file.exists() && file.delete()) {
                log.info("Document physical file deleted (explicit config), path={}", filePath);
            }
        } catch (Exception e) {
            log.warn("Failed to delete document physical file, path={}", filePath, e);
        }
    }

    private String getFileType(String filename) {
        if (filename == null) {
            return "UNKNOWN";
        }
        String upper = filename.toUpperCase();
        if (upper.endsWith(".TXT")) {
            return "TXT";
        }
        if (upper.endsWith(".PDF")) {
            return "PDF";
        }
        return "UNKNOWN";
    }
}
