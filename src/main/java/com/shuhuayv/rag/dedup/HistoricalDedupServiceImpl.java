package com.shuhuayv.rag.dedup;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shuhuayv.rag.entity.KbChunk;
import com.shuhuayv.rag.entity.KbDocument;
import com.shuhuayv.rag.entity.KbVectorRecord;
import com.shuhuayv.rag.mapper.KbChunkMapper;
import com.shuhuayv.rag.mapper.KbDocumentMapper;
import com.shuhuayv.rag.mapper.KbVectorRecordMapper;
import com.shuhuayv.rag.vector.service.QdrantSnapshotService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * PR-3 Historical Dedup 服务实现（编排器）。
 *
 * <p><b>执行流程</b>：</p>
 * <ol>
 *   <li>前置条件校验（fail-closed，见 {@link #verifyPreconditions()}）；</li>
 *   <li>加载 active 且 hash 非空的行，按 {@code content_sha256} 分组；</li>
 *   <li>对每组候选批量读取 {@code kb_chunk} / {@code kb_vector_record} 计算向量完整度；
 *       vector inventory anomaly → HARD FAIL；</li>
 *   <li>用 {@link CanonicalDocumentSelector} 选出唯一 winner（VECTOR_COMPLETENESS → STATUS_RANK
 *       → CREATED_AT → ID，通用 deterministic，禁止硬编码 doc id）；</li>
 *   <li>解析 / 生成 batchId（D3，禁止硬编码批次号）；</li>
 *   <li><b>dry-run</b>：只返回计划，不写 DB / Qdrant；</li>
 *   <li><b>真实执行</b>：Phase A = {@link HistoricalDedupTransactionExecutor}（单事务）→
 *       Phase B = {@link QdrantCleanupService}（Qdrant 不在 DB 事务内，失败不反向恢复 DB，
 *       标记 PR3_QDRANT_CLEANUP_PENDING）；Phase B 前按需为 managed collection 创建 snapshot
 *       （本轮 Runner 默认关闭 + dry-run 默认 true，不会真实创建）。</li>
 * </ol>
 *
 * <p><b>本轮 CODE-ONLY</b>：真实历史数据不变；不会连接真实 DB 启动 Runner。</p>
 */
@Slf4j
@Service
public class HistoricalDedupServiceImpl implements HistoricalDedupService {

    private final KbDocumentMapper kbDocumentMapper;
    private final KbChunkMapper kbChunkMapper;
    private final KbVectorRecordMapper kbVectorRecordMapper;
    private final CanonicalDocumentSelector canonicalDocumentSelector;
    private final DedupBatchIdGenerator batchIdGenerator;
    private final HistoricalDedupTransactionExecutor transactionExecutor;
    private final QdrantCleanupService qdrantCleanupService;
    private final QdrantSnapshotService qdrantSnapshotService;
    private final boolean requireSnapshotBeforeCleanup;

    public HistoricalDedupServiceImpl(KbDocumentMapper kbDocumentMapper,
                                      KbChunkMapper kbChunkMapper,
                                      KbVectorRecordMapper kbVectorRecordMapper,
                                      CanonicalDocumentSelector canonicalDocumentSelector,
                                      DedupBatchIdGenerator batchIdGenerator,
                                      HistoricalDedupTransactionExecutor transactionExecutor,
                                      QdrantCleanupService qdrantCleanupService,
                                      QdrantSnapshotService qdrantSnapshotService,
                                      @Value("${app.dedup.qdrant-snapshot-required-before-cleanup:true}")
                                      boolean requireSnapshotBeforeCleanup) {
        this.kbDocumentMapper = kbDocumentMapper;
        this.kbChunkMapper = kbChunkMapper;
        this.kbVectorRecordMapper = kbVectorRecordMapper;
        this.canonicalDocumentSelector = canonicalDocumentSelector;
        this.batchIdGenerator = batchIdGenerator;
        this.transactionExecutor = transactionExecutor;
        this.qdrantCleanupService = qdrantCleanupService;
        this.qdrantSnapshotService = qdrantSnapshotService;
        this.requireSnapshotBeforeCleanup = requireSnapshotBeforeCleanup;
    }

    @Override
    public HistoricalDedupResult executeDedup(HistoricalDedupCommand command) {
        boolean dryRun = command != null && command.dryRun();
        log.info("PR3_EXECUTE start: dryRun={}", dryRun);

        // ①-③ 前置条件（fail-closed）
        verifyPreconditions();

        // 加载 active 且 hash 非空的行
        List<KbDocument> activeDocs = kbDocumentMapper.selectList(new LambdaQueryWrapper<KbDocument>()
                .eq(KbDocument::getIsDeleted, SoftDeleteSemantics.ACTIVE_FLAG)
                .isNotNull(KbDocument::getContentSha256)
                .orderByAsc(KbDocument::getId));
        if (activeDocs == null) {
            activeDocs = List.of();
        }

        // 按 content_sha256 分组，筛出 size > 1 的重复组；按 hash 排序保证结果确定性
        Map<String, List<KbDocument>> groupsByHash = activeDocs.stream()
                .filter(d -> d.getContentSha256() != null)
                .collect(Collectors.groupingBy(KbDocument::getContentSha256));
        List<List<KbDocument>> dupGroups = groupsByHash.entrySet().stream()
                .filter(entry -> entry.getValue().size() > 1)
                .sorted(Map.Entry.comparingByKey())
                .map(Map.Entry::getValue)
                .toList();

        // ④ clean no-op：无重复组不报错
        if (dupGroups.isEmpty()) {
            log.info("PR3 no active duplicate groups, clean no-op");
            return HistoricalDedupResult.empty(dryRun);
        }

        // ⑤ 已治理 / partial state 判定（fail-closed 禁止覆盖）
        assertNoExistingGovernance(dupGroups);

        // 批量读取 chunk / vector lineage，构造候选并检测 vector inventory anomaly（⑥）
        Set<Long> candidateIds = dupGroups.stream()
                .flatMap(List::stream)
                .map(KbDocument::getId)
                .collect(Collectors.toSet());
        List<KbChunk> allChunks = safeSelectChunks(candidateIds);
        List<KbVectorRecord> allVectors = safeSelectVectors(candidateIds);
        Map<Long, Long> chunkCounts = countByDocument(allChunks);
        Map<Long, List<KbVectorRecord>> vectorsByDocument = groupVectorsByDocument(allVectors);

        List<DedupGroup> resolvedGroups = new ArrayList<>();
        for (List<KbDocument> group : dupGroups) {
            List<CanonicalCandidate> candidates = new ArrayList<>();
            for (KbDocument doc : group) {
                long chunkCount = chunkCounts.getOrDefault(doc.getId(), 0L);
                List<KbVectorRecord> vectors = vectorsByDocument.getOrDefault(doc.getId(), List.of());
                assertNoChunkLevelAnomaly(doc.getId(), vectors); // 同 chunk 重复 vector record → anomaly
                candidates.add(new CanonicalCandidate(doc, chunkCount, vectors.size()));
            }
            // vector_record_count > chunk_count → anomaly（fail-closed）
            for (CanonicalCandidate candidate : candidates) {
                if (candidate.completeness() == VectorCompleteness.VECTOR_INVENTORY_ANOMALY) {
                    throw new IllegalStateException(
                            "PR-3 前置条件失败: vector inventory anomaly，documentId="
                                    + candidate.document().getId());
                }
            }
            // ⑦ 每组恰好 1 winner（selector 通用 deterministic）
            CanonicalCandidate winner = canonicalDocumentSelector.selectWinner(candidates);
            List<KbDocument> duplicates = group.stream()
                    .filter(d -> !d.getId().equals(winner.document().getId()))
                    .sorted(Comparator.comparing(KbDocument::getId))
                    .toList();
            resolvedGroups.add(new DedupGroup(group, winner.document(), duplicates));
        }

        // ⑧ batchId 合法且 <= 32
        String batchId = resolveBatchId(command);

        List<Long> canonicalIds = resolvedGroups.stream()
                .map(group -> group.winner().getId())
                .toList();
        List<Long> duplicateIds = resolvedGroups.stream()
                .flatMap(group -> group.duplicates().stream())
                .map(KbDocument::getId)
                .toList();
        log.info("PR3 plan: dryRun={}, batchId={}, groups={}, winners={}, duplicates={}",
                dryRun, batchId, resolvedGroups.size(), canonicalIds, duplicateIds);

        if (dryRun) {
            return new HistoricalDedupResult(true, batchId, resolvedGroups.size(), canonicalIds.size(),
                    duplicateIds.size(), canonicalIds, duplicateIds, false, 0, 0,
                    List.of("dry-run 未执行任何 DB/Qdrant 写入"));
        }

        // Phase A：单个 MySQL 事务
        DedupWriteResult write = transactionExecutor.canonicalize(resolvedGroups, batchId);

        // Phase B：Qdrant 补偿清理（不在 DB 事务内）
        return executeQdrantPhase(batchId, resolvedGroups, write);
    }

    // ==================== 前置条件（fail-closed，J 组测试契约） ====================

    /**
     * 真实执行前的 fail-closed 前置条件（①-③；④ clean no-op；⑤⑥⑦⑧ 在后续流程中校验）。
     *
     * @throws IllegalStateException 任一前置条件不满足
     */
    void verifyPreconditions() {
        // ① M1 四列必须存在
        int m1Columns = kbDocumentMapper.countM1IdentityColumns();
        if (m1Columns != 4) {
            throw new IllegalStateException(
                    "PR-3 前置条件失败: M1 四列必须存在（content_sha256/is_deleted/canonical_document_id/dedup_batch），found=" + m1Columns);
        }
        // ② M2 唯一索引必须不存在
        int m2IndexCount = kbDocumentMapper.countUniqueIndexOnContentIdentity();
        if (m2IndexCount > 0) {
            throw new IllegalStateException(
                    "PR-3 前置条件失败: M2 唯一索引（content_sha256, is_deleted）已存在，禁止在 M2 之后执行 PR-3");
        }
        // ③ active NULL hash 必须为 0
        long activeNullHash = kbDocumentMapper.countActiveNullHash();
        if (activeNullHash > 0) {
            throw new IllegalStateException(
                    "PR-3 前置条件失败: active NULL hash 行数=" + activeNullHash + "，必须先完成 content_sha256 backfill");
        }
    }

    private void assertNoExistingGovernance(List<List<KbDocument>> dupGroups) {
        for (List<KbDocument> group : dupGroups) {
            for (KbDocument doc : group) {
                if (doc.getCanonicalDocumentId() != null
                        || (doc.getDedupBatch() != null && !doc.getDedupBatch().isBlank())) {
                    // 未知 partial state：禁止覆盖
                    throw new IllegalStateException(
                            "PR-3 前置条件失败: 检测到已治理/partial 状态（canonical_document_id 或 dedup_batch 非空），documentId="
                                    + doc.getId() + "，禁止覆盖");
                }
            }
        }
    }

    private void assertNoChunkLevelAnomaly(Long documentId, List<KbVectorRecord> vectors) {
        Map<Long, Integer> countByChunk = new HashMap<>();
        for (KbVectorRecord vector : vectors) {
            if (vector.getChunkId() == null) {
                continue;
            }
            countByChunk.merge(vector.getChunkId(), 1, Integer::sum);
        }
        for (Map.Entry<Long, Integer> entry : countByChunk.entrySet()) {
            if (entry.getValue() > 1) {
                throw new IllegalStateException(
                        "PR-3 前置条件失败: 同 chunk 异常重复 vector record（documentId=" + documentId
                                + ", chunkId=" + entry.getKey() + ", count=" + entry.getValue() + "）");
            }
        }
    }

    // ==================== 批量 lineage 读取（避免 N+1） ====================

    private List<KbChunk> safeSelectChunks(Set<Long> candidateIds) {
        if (candidateIds.isEmpty()) {
            return List.of();
        }
        List<KbChunk> chunks = kbChunkMapper.selectList(new LambdaQueryWrapper<KbChunk>()
                .in(KbChunk::getDocumentId, candidateIds));
        return chunks == null ? List.of() : chunks;
    }

    private List<KbVectorRecord> safeSelectVectors(Set<Long> candidateIds) {
        if (candidateIds.isEmpty()) {
            return List.of();
        }
        List<KbVectorRecord> vectors = kbVectorRecordMapper.selectList(new LambdaQueryWrapper<KbVectorRecord>()
                .in(KbVectorRecord::getDocumentId, candidateIds));
        return vectors == null ? List.of() : vectors;
    }

    private static Map<Long, Long> countByDocument(List<KbChunk> chunks) {
        Map<Long, Long> counts = new HashMap<>();
        for (KbChunk chunk : chunks) {
            if (chunk != null && chunk.getDocumentId() != null) {
                counts.merge(chunk.getDocumentId(), 1L, Long::sum);
            }
        }
        return counts;
    }

    private static Map<Long, List<KbVectorRecord>> groupVectorsByDocument(List<KbVectorRecord> vectors) {
        Map<Long, List<KbVectorRecord>> grouped = new HashMap<>();
        for (KbVectorRecord vector : vectors) {
            if (vector != null && vector.getDocumentId() != null) {
                grouped.computeIfAbsent(vector.getDocumentId(), k -> new ArrayList<>()).add(vector);
            }
        }
        return grouped;
    }

    // ==================== batchId（D3） ====================

    private String resolveBatchId(HistoricalDedupCommand command) {
        if (command != null && command.batchId() != null && !command.batchId().isBlank()) {
            if (!batchIdGenerator.isValid(command.batchId())) {
                throw new IllegalStateException("PR-3 前置条件失败: batchId 非法（格式或长度），value=" + command.batchId());
            }
            return command.batchId();
        }
        LocalDate today = LocalDate.now();
        String prefix = "dedup-" + today.format(DateTimeFormatter.BASIC_ISO_DATE) + "-";
        Long count = kbDocumentMapper.selectCount(new LambdaQueryWrapper<KbDocument>()
                .likeRight(KbDocument::getDedupBatch, prefix));
        long sequence = (count == null ? 0L : count) + 1L;
        if (sequence > 99) {
            throw new IllegalStateException("PR-3 前置条件失败: 当日批次序号超上限（>99），无法生成合法 batchId");
        }
        String generated = batchIdGenerator.generate(today, (int) sequence);
        log.info("PR3 generated batchId={}", generated);
        return generated;
    }

    // ==================== Phase B：Qdrant 补偿清理 ====================

    private HistoricalDedupResult executeQdrantPhase(String batchId, List<DedupGroup> resolvedGroups,
                                                     DedupWriteResult write) {
        List<Long> duplicateIds = resolvedGroups.stream()
                .flatMap(group -> group.duplicates().stream())
                .map(KbDocument::getId)
                .toList();
        List<QdrantCleanupTarget> targets = buildCleanupTargets(duplicateIds);
        if (targets.isEmpty()) {
            return new HistoricalDedupResult(false, batchId, resolvedGroups.size(), write.winners(),
                    write.duplicates(), write.canonicalIds(), write.duplicateIds(), false, 0, 0,
                    List.of("无 Qdrant points 需要清理"));
        }

        // 真实删除前必须为 managed collection 创建 snapshot（PR3_REQUIRES_QDRANT_SNAPSHOT=YES）。
        // 本轮 CODE-ONLY：Runner 默认关闭 + dry-run 默认 true，本分支不会真实执行。
        if (requireSnapshotBeforeCleanup) {
            for (QdrantCleanupTarget target : targets) {
                if (target.collectionName() == null || target.collectionName().isBlank()) {
                    continue;
                }
                qdrantSnapshotService.createSnapshot(target.collectionName());
                log.info("PR3 snapshot created before cleanup: collection={}", target.collectionName());
            }
        }

        try {
            QdrantCleanupResult cleanup = qdrantCleanupService.cleanupDuplicatePoints(targets);
            return new HistoricalDedupResult(false, batchId, resolvedGroups.size(), write.winners(),
                    write.duplicates(), write.canonicalIds(), write.duplicateIds(), false,
                    cleanup.managedCollectionsCleaned(), cleanup.legacyCollectionsSkipped(),
                    List.of("Qdrant cleanup 完成"));
        } catch (Exception e) {
            // MySQL 成功 + Qdrant cleanup 失败：DB 保持治理状态，不自动反向恢复 active；
            // 记录 PR3_QDRANT_CLEANUP_PENDING，允许仅重跑 cleanup 阶段。
            log.error("PR3 Qdrant cleanup 失败，DB 已保持治理状态，不自动反向恢复；记录 PR3_QDRANT_CLEANUP_PENDING", e);
            return new HistoricalDedupResult(false, batchId, resolvedGroups.size(), write.winners(),
                    write.duplicates(), write.canonicalIds(), write.duplicateIds(), true, 0, 0,
                    List.of("PR3_QDRANT_CLEANUP_PENDING: " + e.getMessage()));
        }
    }

    /**
     * 由 duplicate 文档的 kb_vector_record lineage 构造 exact-point 清理目标。
     *
     * <p>只从 lineage 取点：legacy mock orphan points（无 kb_vector_record）天然不在目标内；
     * 具体 collection 是否可清理由 {@link QdrantCleanupService} 的 managed inventory 把关。</p>
     */
    List<QdrantCleanupTarget> buildCleanupTargets(List<Long> duplicateIds) {
        if (duplicateIds == null || duplicateIds.isEmpty()) {
            return List.of();
        }
        List<KbVectorRecord> records = safeSelectVectors(new HashSet<>(duplicateIds));
        if (records.isEmpty()) {
            return List.of();
        }
        return QdrantCleanupServiceImpl.buildTargets(records);
    }
}
