package com.shuhuayv.rag.dedup;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shuhuayv.rag.entity.KbVectorRecord;
import com.shuhuayv.rag.mapper.KbVectorRecordMapper;
import com.shuhuayv.rag.vector.service.QdrantVectorService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * {@link QdrantCleanupService} 实现。
 *
 * <p>current managed collections inventory 来自配置
 * {@code app.dedup.qdrant-managed-collections}（逗号分隔，默认
 * {@code kb_chunks_zhipu_embedding_3_1024_v1}）。legacy mock {@code kb_chunks} 不在其中，
 * 因此即使有清理目标也会被自动跳过。</p>
 */
@Slf4j
@Service
public class QdrantCleanupServiceImpl implements QdrantCleanupService {

    private final QdrantVectorService qdrantVectorService;
    private final KbVectorRecordMapper kbVectorRecordMapper;
    private final Set<String> managedCollections;

    public QdrantCleanupServiceImpl(QdrantVectorService qdrantVectorService,
                                    KbVectorRecordMapper kbVectorRecordMapper,
                                    @Value("${app.dedup.qdrant-managed-collections:kb_chunks_zhipu_embedding_3_1024_v1}")
                                    String managedCollectionsCsv) {
        this.qdrantVectorService = qdrantVectorService;
        this.kbVectorRecordMapper = kbVectorRecordMapper;
        this.managedCollections = parseCsv(managedCollectionsCsv);
    }

    @Override
    public QdrantCleanupResult cleanupDuplicatePoints(List<QdrantCleanupTarget> targets) {
        if (targets == null || targets.isEmpty()) {
            return QdrantCleanupResult.none();
        }

        int managedCleaned = 0;
        int legacySkipped = 0;
        for (QdrantCleanupTarget target : targets) {
            String collectionName = target.collectionName();
            if (collectionName == null || collectionName.isBlank()
                    || !managedCollections.contains(collectionName)) {
                // 非 managed inventory：自动跳过，绝不自动清理（PR3_MOCK_LEGACY_POINT_CLEANUP=NO）。
                legacySkipped++;
                log.warn("PR3 Qdrant cleanup 跳过非 managed collection（不自动清理）: {}", collectionName);
                continue;
            }
            List<String> pointIds = target.pointIds();
            if (pointIds == null || pointIds.isEmpty()) {
                continue;
            }
            // exact point IDs 删除；delete 已不存在的 ID 视为 already-clean/success（幂等）。
            qdrantVectorService.deletePoints(collectionName, pointIds, true);
            managedCleaned++;
            log.info("PR3 Qdrant cleanup 完成: collection={}, pointIds={}", collectionName, pointIds.size());
        }
        return new QdrantCleanupResult(managedCleaned, legacySkipped, false);
    }

    @Override
    public QdrantCleanupResult cleanupForDocument(Long documentId) {
        if (documentId == null) {
            throw new IllegalArgumentException("documentId 不能为空");
        }
        List<KbVectorRecord> records = kbVectorRecordMapper.selectList(new LambdaQueryWrapper<KbVectorRecord>()
                .eq(KbVectorRecord::getDocumentId, documentId));
        if (records == null || records.isEmpty()) {
            return QdrantCleanupResult.none();
        }
        return cleanupDuplicatePoints(buildTargets(records));
    }

    /**
     * 由 kb_vector_record lineage 构造 collection → exact point IDs 清理目标。
     *
     * <p>只从 lineage 取点：无 lineage 的 legacy mock orphan points 天然不在目标内。</p>
     */
    static List<QdrantCleanupTarget> buildTargets(List<KbVectorRecord> records) {
        LinkedHashMap<String, LinkedHashSet<String>> byCollection = new LinkedHashMap<>();
        for (KbVectorRecord record : records) {
            if (record == null) {
                continue;
            }
            String collectionName = record.getCollectionName();
            String pointId = record.getQdrantPointId();
            if (collectionName == null || collectionName.isBlank()) {
                continue;
            }
            if (pointId == null || pointId.isBlank()) {
                continue;
            }
            byCollection.computeIfAbsent(collectionName, k -> new LinkedHashSet<>()).add(pointId);
        }
        List<QdrantCleanupTarget> targets = new ArrayList<>();
        byCollection.forEach((collectionName, pointIds) ->
                targets.add(new QdrantCleanupTarget(collectionName, new ArrayList<>(pointIds))));
        return targets;
    }

    private static Set<String> parseCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return Set.of();
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .forEach(result::add);
        return result;
    }
}
